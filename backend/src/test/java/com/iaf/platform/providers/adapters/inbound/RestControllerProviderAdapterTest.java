package com.iaf.platform.providers.adapters.inbound;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.iaf.platform.assistant.domain.ProviderDraft;
import com.iaf.platform.common.domain.CommandAccepted;
import com.iaf.platform.common.domain.ConflictException;
import com.iaf.platform.common.domain.Effect;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.common.domain.Problem;
import com.iaf.platform.providers.application.ProviderCommandHandler;
import com.iaf.platform.providers.application.ProviderQueryHandler;
import com.iaf.platform.providers.domain.ActionInfo;
import com.iaf.platform.providers.domain.ConnectionResult;
import com.iaf.platform.providers.domain.Health;
import com.iaf.platform.providers.domain.ProviderAlert;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.providers.domain.ProviderDetail;
import com.iaf.platform.providers.domain.ProviderSummary;
import com.iaf.platform.providers.domain.ProviderReview;
import com.iaf.platform.providers.domain.ProviderYaml;
import com.iaf.platform.providers.domain.ReviewEvidence;
import com.iaf.platform.providers.domain.SpecImport;
import com.iaf.platform.providers.domain.TryResult;
import com.iaf.platform.providers.ports.inbound.ProviderCommands;
import com.iaf.platform.providers.ports.inbound.ProviderCommands.ImportSpec;
import com.iaf.platform.providers.ports.inbound.ProviderCommands.RefreshSpec;
import com.iaf.platform.providers.ports.inbound.ProviderCommands.SetSlo;
import com.iaf.platform.providers.ports.inbound.ProviderCommands.ValidationResult;
import com.iaf.platform.testing.TestWeb;
import com.iaf.platform.workflows.domain.ChangeRequest.Check;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class RestControllerProviderAdapterTest {

    static final String YAML = """
            apiVersion: iaf.platform/v1
            kind: Provider
            metadata: { name: fleet, title: Fleet API, owner: team-compute, version: 1.0.0 }
            spec:
              type: openapi
              spec: { url: https://fleet.example.com/openapi.json, sha256: 9f2c1b7e0a4d5c6b8e9f0a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d }
              endpoints: { dev: https://fleet-dev.example.com }
              auth: { type: bearer, credential: vault:fleet/token }
              actions:
                - { id: vm.get, title: Get VM, operation: getVm, effect: read, retry: safe }
              slo: { successRate: 99.5, p95Ms: 800 }
              alerts: { channel: "#compute-alerts" }
            """;

    ProviderCommandHandler commands;
    ProviderQueryHandler queries;
    WebTestClient client;
    ProviderDefinition fleet;
    Health healthy;

    @BeforeEach
    void setUp() {
        commands = mock(ProviderCommandHandler.class);
        queries = mock(ProviderQueryHandler.class);
        client = TestWeb.client(new RestControllerProviderAdapter(commands, queries));
        fleet = ProviderYaml.parse(YAML);
        healthy = new Health(Health.Status.HEALTHY, 99.9, 100, 200, 42, List.of(0, 1), List.of());
    }

    @Test
    void listsProviders() {
        when(queries.list()).thenReturn(Flux.just(ProviderSummary.of(fleet, healthy)));
        client.get().uri("/api/v1/providers").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].name").isEqualTo("fleet")
                .jsonPath("$[0].type").isEqualTo("openapi")
                .jsonPath("$[0].environments[0]").isEqualTo("dev")
                .jsonPath("$[0].actionCount").isEqualTo(1)
                .jsonPath("$[0].health.status").isEqualTo("healthy")
                .jsonPath("$[0].health.errorTrend[1]").isEqualTo(1)
                .jsonPath("$[0].slo.p95Ms").isEqualTo(800)
                .jsonPath("$[0].alerts.channel").isEqualTo("#compute-alerts");
    }

    @Test
    void showsADetailAndA404ForUnknownNames() {
        ProviderDetail detail = ProviderDetail.of(ProviderSummary.of(fleet, healthy), fleet, YAML, List.of(ActionInfo.of(fleet, fleet.actions().get(0))),
                List.of(new ProviderDetail.Daily("2026-10-16T10:00:00Z", 3, 100.0, 90L, 120L), new ProviderDetail.Daily("2026-10-17T10:00:00Z", 0, null, null, null)),
                List.of(new ProviderDetail.PerAction("vm.get", 3, 100.0, 90, 120, List.of(new ProviderDetail.ErrorCount("timeout", 1)))), List.of());
        when(queries.detail("fleet")).thenReturn(Mono.just(detail));
        when(queries.detail("nope")).thenReturn(Mono.error(new NotFoundException("Provider", "nope")));
        client.get().uri("/api/v1/providers/fleet").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.yaml").isEqualTo(YAML)
                .jsonPath("$.actions[0].id").isEqualTo("vm.get")
                .jsonPath("$.actions[0].effect").isEqualTo("read")
                .jsonPath("$.actions[0].overridden").isEqualTo(false)
                .jsonPath("$.daily[0].date").isEqualTo("2026-10-16T10:00:00Z")
                .jsonPath("$.daily[1].calls").isEqualTo(0)
                .jsonPath("$.daily[1].successRate").doesNotExist()
                .jsonPath("$.endpoints.dev").isEqualTo("https://fleet-dev.example.com")
                .jsonPath("$.auth.type").isEqualTo("bearer")
                .jsonPath("$.auth.credential").isEqualTo("vault:fleet/token")
                .jsonPath("$.perAction[0].topErrors[0].class").isEqualTo("timeout")
                .jsonPath("$.health.status").isEqualTo("healthy");
        client.get().uri("/api/v1/providers/nope").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.error").isEqualTo("Provider nope not found");
    }

    @Test
    void listsAlerts() {
        when(queries.alerts("fleet")).thenReturn(Flux.just(new ProviderAlert("alert_1", Instant.parse("2026-10-17T10:00:00Z"), "fleet", "vm.get", "Call vm.get failed", "warning", "job_1", "#compute-alerts")));
        client.get().uri("/api/v1/providers/fleet/alerts").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].id").isEqualTo("alert_1")
                .jsonPath("$[0].severity").isEqualTo("warning")
                .jsonPath("$[0].jobId").isEqualTo("job_1")
                .jsonPath("$[0].channel").isEqualTo("#compute-alerts")
                .jsonPath("$[0].at").isEqualTo("2026-10-17T10:00:00Z");
    }

    @Test
    void importsASpec() {
        SpecImport spec = new SpecImport("Machines", "1", "abc", new SpecImport.AuthInfo("bearer", null), List.of("https://m.example.com"),
                List.of(new SpecImport.Operation("getVm", "GET", "/vms/{id}", "Get", Effect.READ, "safe", JsonNodeFactory.instance.objectNode(), null)));
        when(commands.importSpec(any())).thenReturn(Mono.just(spec));
        client.post().uri("/api/v1/providers:import-spec").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("type", "openapi", "url", "https://m.example.com/openapi.json")).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.title").isEqualTo("Machines")
                .jsonPath("$.auth.type").isEqualTo("bearer")
                .jsonPath("$.operations[0].effect").isEqualTo("read")
                .jsonPath("$.operations[0].retry").isEqualTo("safe");
    }

    @Test
    void testsConnectionsTriesActionsAndValidates() {
        when(commands.testConnection(any())).thenReturn(Mono.just(new ConnectionResult.Results(List.of(new ConnectionResult("dev", true, 12, "HEAD 200")))));
        client.post().uri("/api/v1/providers:test-connection").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("endpoints", Map.of("dev", "https://fleet-dev.example.com"), "auth", Map.of("type", "none"))).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.results[0].environment").isEqualTo("dev").jsonPath("$.results[0].ok").isEqualTo(true);

        when(commands.tryAction(any())).thenReturn(Mono.just(new TryResult(true, 200, 33, JsonNodeFactory.instance.objectNode().put("id", "vm-1"), List.of(), null)));
        client.post().uri("/api/v1/providers:try").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("yaml", YAML, "action", "vm.get", "environment", "dev", "input", Map.of("id", "vm-1"))).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.ok").isEqualTo(true).jsonPath("$.response.id").isEqualTo("vm-1").jsonPath("$.durationMs").isEqualTo(33);

        when(commands.validate(any())).thenReturn(Mono.just(new ValidationResult(List.of(new Check("schema", "Schema", "passed", null)),
                List.of(Problem.warning("action.idShape", "getVm", "spec.actions[0].id", "Action ids read as <noun>.<verb>")))));
        client.post().uri("/api/v1/providers:validate").contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("yaml", YAML)).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.checks[0].id").isEqualTo("schema")
                .jsonPath("$.problems[0].level").isEqualTo("warning")
                .jsonPath("$.problems[0].step").isEqualTo("getVm");
    }

    @Test
    void theWizardNamesTheProviderItProvesAndGetsTheReviewBack() {
        when(commands.testConnection(any())).thenReturn(Mono.just(new ConnectionResult.Results(List.of(new ConnectionResult("dev", true, 12, "HEAD 200")))));
        client.post().uri("/api/v1/providers:test-connection").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("endpoints", Map.of("dev", "https://fleet-dev.example.com"), "auth", Map.of("type", "none"), "provider", "fleet")).exchange()
                .expectStatus().isOk();
        ArgumentCaptor<ProviderCommands.TestConnection> sent = ArgumentCaptor.forClass(ProviderCommands.TestConnection.class);
        verify(commands).testConnection(sent.capture());
        Assertions.assertThat(sent.getValue().provider()).isEqualTo("fleet");

        when(commands.tryAction(any())).thenReturn(Mono.just(new TryResult(true, 200, 33, JsonNodeFactory.instance.objectNode().put("id", "vm-1"), List.of(),
                null, "dev", Instant.parse("2026-10-17T10:00:00Z"), YAML + "# with the sample\n")));
        client.post().uri("/api/v1/providers:try").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("yaml", YAML, "action", "vm.get", "environment", "dev")).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.environment").isEqualTo("dev")
                .jsonPath("$.at").isEqualTo("2026-10-17T10:00:00Z")
                .jsonPath("$.yaml").value(y -> Assertions.assertThat((String) y).endsWith("# with the sample\n"));

        ProviderReview.Result review = ProviderReview.review(fleet, ReviewEvidence.NONE);
        when(commands.validate(any())).thenReturn(Mono.just(new ValidationResult(List.of(new Check("schema", "Schema", "passed", null)), List.of(), review)));
        client.post().uri("/api/v1/providers:validate").contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("yaml", YAML)).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.review.ready").isEqualTo(false)
                .jsonPath("$.review.checks[0].id").isEqualTo("connection")
                .jsonPath("$.review.checks[0].status").isEqualTo("failed")
                .jsonPath("$.review.problems[0].code").isEqualTo("connection.untested")
                .jsonPath("$.review.slo").doesNotExist();
    }

    @Test
    void publishesWith202AndConflictsCarryTheirDetail() {
        when(commands.publish(any())).thenReturn(Mono.just(CommandAccepted.of("cr_1", "open", Map.of("changeRequest", "/api/v1/change-requests/cr_1"))));
        client.post().uri("/api/v1/providers:publish").contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("yaml", YAML, "strategy", "all")).exchange()
                .expectStatus().isAccepted()
                .expectBody().jsonPath("$.id").isEqualTo("cr_1").jsonPath("$.status").isEqualTo("open").jsonPath("$.links.changeRequest").isEqualTo("/api/v1/change-requests/cr_1");

        when(commands.publish(any())).thenReturn(Mono.error(new ConflictException("The provider has 1 problem to fix before it can be published",
                List.of(Problem.error("credential.plain", null, "spec.auth.credential", "Secrets never go in a provider file")))));
        client.post().uri("/api/v1/providers:publish").contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("yaml", YAML)).exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.error").isEqualTo("The provider has 1 problem to fix before it can be published")
                .jsonPath("$.detail[0].code").isEqualTo("credential.plain");
    }

    @Test
    void draftsOverridesAndSlo() {
        when(commands.draft(any())).thenReturn(Mono.just(new ProviderDraft("kind: Provider", "A draft", List.of("bearer"), List.of())));
        client.post().uri("/api/v1/providers:draft").contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("description", "the fleet api")).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.yaml").isEqualTo("kind: Provider").jsonPath("$.assumptions[0]").isEqualTo("bearer");

        when(commands.overrides(eq("fleet"), any())).thenReturn(Mono.just(CommandAccepted.of("fleet", "updated")));
        client.post().uri("/api/v1/providers/fleet/overrides").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("action", "vm.get", "response", Map.of("id", "vm-1"))).exchange()
                .expectStatus().isAccepted()
                .expectBody().jsonPath("$.status").isEqualTo("updated");

        when(commands.slo(eq("fleet"), any())).thenReturn(Mono.just(CommandAccepted.of("fleet", "updated")));
        client.put().uri("/api/v1/providers/fleet/slo").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("successRate", 99.9, "p95Ms", 500, "channel", "#x")).exchange()
                .expectStatus().isAccepted();
        ArgumentCaptor<SetSlo> captor = ArgumentCaptor.forClass(SetSlo.class);
        verify(commands).slo(eq("fleet"), captor.capture());
        Assertions.assertThat(captor.getValue().successRate()).isEqualTo(99.9);
        Assertions.assertThat(captor.getValue().p95Ms()).isEqualTo(500);
        Assertions.assertThat(captor.getValue().channel()).isEqualTo("#x");
    }

    @Test
    void aSpecFromGitComesBackWithItsCommit() {
        SpecImport spec = new SpecImport("Machines", "1", "abc", new SpecImport.AuthInfo("bearer", null), List.of(), List.of(), "3f2a9c1d");
        when(commands.importSpec(any())).thenReturn(Mono.just(spec));
        client.post().uri("/api/v1/providers:import-spec").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("type", "openapi", "git", Map.of("repo", "https://github.com/acme/machines-api", "ref", "v2.4.0", "path", "openapi.yaml",
                        "credential", "vault:github/read-token")))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.commit").isEqualTo("3f2a9c1d");
        ArgumentCaptor<ImportSpec> captor = ArgumentCaptor.forClass(ImportSpec.class);
        verify(commands).importSpec(captor.capture());
        Assertions.assertThat(captor.getValue().git()).isEqualTo(new ProviderDefinition.GitRef("https://github.com/acme/machines-api", "v2.4.0", "openapi.yaml",
                "vault:github/read-token"));
    }

    @Test
    void refreshingASpecIs202WithOrWithoutABody() {
        when(commands.refreshSpec(eq("fleet"), any())).thenReturn(Mono.just(CommandAccepted.of("cr-9", "open")));
        client.post().uri("/api/v1/providers/fleet:refresh-spec").contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("ref", "v2.5.0")).exchange()
                .expectStatus().isAccepted()
                .expectBody().jsonPath("$.id").isEqualTo("cr-9").jsonPath("$.status").isEqualTo("open");
        ArgumentCaptor<RefreshSpec> captor = ArgumentCaptor.forClass(RefreshSpec.class);
        verify(commands).refreshSpec(eq("fleet"), captor.capture());
        Assertions.assertThat(captor.getValue().ref()).isEqualTo("v2.5.0");

        when(commands.refreshSpec(eq("machines"), any())).thenReturn(Mono.just(CommandAccepted.of("machines", "unchanged")));
        client.post().uri("/api/v1/providers/machines:refresh-spec").exchange()
                .expectStatus().isAccepted()
                .expectBody().jsonPath("$.status").isEqualTo("unchanged");

        when(commands.refreshSpec(eq("gone"), any())).thenReturn(Mono.error(new ConflictException("The new spec drops 1 operation",
                List.of(Problem.error("refresh.operationGone", "vm.delete", "spec.actions[1].operation", "The new spec has no operation deleteVm")))));
        client.post().uri("/api/v1/providers/gone:refresh-spec").exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.error").isEqualTo("The new spec drops 1 operation");
    }

    @Test
    void badInputIs400() {
        when(commands.tryAction(any())).thenReturn(Mono.error(new IllegalArgumentException("action is required")));
        client.post().uri("/api/v1/providers:try").contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("yaml", YAML)).exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("action is required");
    }
}
