package com.iaf.platform.providers.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.assistant.domain.ProviderDraft;
import com.iaf.platform.assistant.ports.inbound.AssistantCommands;
import com.iaf.platform.common.domain.ConflictException;
import com.iaf.platform.common.domain.ForbiddenException;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.common.domain.Problem;
import com.iaf.platform.common.events.DomainEvent;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.identity.application.CurrentIdentity;
import com.iaf.platform.providers.domain.ProviderYaml;
import com.iaf.platform.providers.ports.inbound.ProviderCommands.Draft;
import com.iaf.platform.providers.ports.inbound.ProviderCommands.ImportSpec;
import com.iaf.platform.providers.ports.inbound.ProviderCommands.Publish;
import com.iaf.platform.providers.ports.inbound.ProviderCommands.SetOverride;
import com.iaf.platform.providers.ports.inbound.ProviderCommands.SetSlo;
import com.iaf.platform.providers.ports.inbound.ProviderCommands.TestConnection;
import com.iaf.platform.providers.ports.inbound.ProviderCommands.TryAction;
import com.iaf.platform.providers.ports.inbound.ProviderCommands.Validate;
import com.iaf.platform.providers.ports.outbound.HttpProbePort;
import com.iaf.platform.providers.ports.outbound.HttpProbePort.ProbeResult;
import com.iaf.platform.providers.ports.outbound.ProviderStorePort;
import com.iaf.platform.providers.ports.outbound.ProviderStorePort.StoredProvider;
import com.iaf.platform.runs.ports.outbound.ProviderCallPort;
import com.iaf.platform.runs.ports.outbound.ProviderCallPort.ProviderCall;
import com.iaf.platform.runs.ports.outbound.ProviderCallPort.ProviderCallException;
import com.iaf.platform.workflows.domain.ChangeRequest;
import com.iaf.platform.workflows.ports.outbound.ChangeRequestPort;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ProviderCommandHandlerTest {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final Identity OWNER = new Identity("mateo", "Mateo", null, List.of("team-compute"), List.of("ops"), false, Identity.Kind.USER);
    static final Identity OUTSIDER = new Identity("ana", "Ana", null, List.of("team-data"), List.of("ops"), false, Identity.Kind.USER);
    static final ObjectMapper JSON = new ObjectMapper();
    static final String YAML = """
            apiVersion: iaf.platform/v1
            kind: Provider
            metadata: { name: fleet, title: Fleet API, owner: team-compute, version: 1.1.0 }
            spec:
              type: openapi
              spec: { url: https://fleet.example.com/openapi.json, sha256: 9f2c1b7e0a4d5c6b8e9f0a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d }
              endpoints: { dev: https://fleet-dev.example.com, prod: https://fleet.example.com }
              auth: { type: bearer, credential: vault:fleet/token }
              actions:
                - id: vm.get
                  title: Get VM
                  operation: getVm
                  effect: read
                  retry: safe
                  output:
                    type: object
                    properties:
                      id: { type: string }
                      name: { type: string }
                      powerState: { type: string, enum: [poweredOn, poweredOff] }
                - id: vm.delete
                  title: Delete VM
                  operation: deleteVm
                  effect: delete
                  retry: unsafe
                  approval: required
                  verify: { action: vm.get, expect: notFound }
              slo: { successRate: 99.5, p95Ms: 800 }
              alerts: { channel: "#compute-alerts" }
            """;
    static final String YAML_V1 = YAML.replace("version: 1.1.0", "version: 1.0.0");

    ProviderStorePort store;
    ChangeRequestPort changeRequests;
    ProviderCallPort calls;
    AssistantCommands assistant;
    HttpProbePort http;
    EventBus events;
    List<DomainEvent> published;
    ProviderCommandHandler handler;

    @BeforeEach
    void setUp() {
        store = mock(ProviderStorePort.class);
        changeRequests = mock(ChangeRequestPort.class);
        calls = mock(ProviderCallPort.class);
        assistant = mock(AssistantCommands.class);
        http = mock(HttpProbePort.class);
        events = new EventBus(CLOCK);
        published = new ArrayList<>();
        events.register(published::add);
        handler = new ProviderCommandHandler(store, changeRequests, calls, assistant, http, events, CLOCK);
        handler.listen();
    }

    static <T> Mono<T> as(Mono<T> mono, Identity who) {
        return mono.contextWrite(ctx -> CurrentIdentity.with(ctx, who));
    }

    static StoredProvider stored(String yaml) {
        return new StoredProvider(ProviderYaml.parse(yaml), yaml, "providers/team-compute/fleet.yaml", NOW.minusSeconds(3600), "content");
    }

    static String spec() {
        try (InputStream in = ProviderCommandHandlerTest.class.getResourceAsStream("/openapi/machines-api.yaml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static JsonNode json(String s) {
        try {
            return JSON.readTree(s);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    Optional<DomainEvent> event(String type) {
        return published.stream().filter(e -> e.type().equals(type)).findFirst();
    }

    // ---------------------------------------------------------------- import spec

    @Test
    void importsAPastedSpec() {
        StepVerifier.create(handler.importSpec(new ImportSpec("openapi", null, spec())))
                .assertNext(s -> {
                    assertThat(s.title()).isEqualTo("Machines API");
                    assertThat(s.operations()).hasSize(8);
                })
                .verifyComplete();
    }

    @Test
    void importsASpecByUrl() {
        when(http.fetch("https://fleet.example.com/openapi.yaml")).thenReturn(Mono.just(spec()));
        StepVerifier.create(handler.importSpec(new ImportSpec("openapi", "https://fleet.example.com/openapi.yaml", null)))
                .assertNext(s -> assertThat(s.sha256()).hasSize(64))
                .verifyComplete();
    }

    @Test
    void anUnreachableSpecIsABadRequest() {
        when(http.fetch(any())).thenReturn(Mono.error(new IllegalStateException("connection refused")));
        StepVerifier.create(handler.importSpec(new ImportSpec("openapi", "https://nowhere.example.com/spec", null)))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("connection refused"))
                .verify();
    }

    @Test
    void onlyOpenapiCanBeImportedAndADocumentIsNeeded() {
        StepVerifier.create(handler.importSpec(new ImportSpec("wsdl", "https://x", null))).expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(handler.importSpec(new ImportSpec("openapi", null, null))).expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(handler.importSpec(new ImportSpec("openapi", null, "swagger: '2.0'\npaths: {}\n"))).expectError(IllegalArgumentException.class).verify();
    }

    // ---------------------------------------------------------------- test connection

    @Test
    void testsEveryEnvironmentInOrderAndNeverFails() {
        Map<String, String> endpoints = new LinkedHashMap<>();
        endpoints.put("dev", "https://fleet-dev.example.com");
        endpoints.put("prod", "https://fleet.example.com");
        endpoints.put("test", "");
        when(http.probe(eq("https://fleet-dev.example.com"), argThat(a -> "bearer".equals(a.type()) && "vault:fleet/token".equals(a.credential()))))
                .thenReturn(Mono.just(new ProbeResult(true, 12, "HEAD 200 in 12 ms")));
        when(http.probe(eq("https://fleet.example.com"), any())).thenReturn(Mono.error(new IllegalStateException("boom")));
        StepVerifier.create(handler.testConnection(new TestConnection(endpoints, json("{\"type\":\"bearer\",\"credential\":\"vault:fleet/token\"}"))))
                .assertNext(r -> {
                    assertThat(r.results()).hasSize(3);
                    assertThat(r.results().get(0).environment()).isEqualTo("dev");
                    assertThat(r.results().get(0).ok()).isTrue();
                    assertThat(r.results().get(0).durationMs()).isEqualTo(12);
                    assertThat(r.results().get(1).ok()).isFalse();
                    assertThat(r.results().get(1).message()).isEqualTo("boom");
                    assertThat(r.results().get(2).message()).contains("No endpoint");
                    assertThat(r.allOk()).isFalse();
                })
                .verifyComplete();
        StepVerifier.create(handler.testConnection(new TestConnection(Map.of(), null))).expectError(IllegalArgumentException.class).verify();
    }

    // ---------------------------------------------------------------- try it

    @Test
    void triesAnActionForRealAndReportsTheMismatches() {
        when(calls.call(any())).thenReturn(Mono.just(json("{\"id\":\"vm-1\",\"name\":\"web\",\"powerState\":\"poweredOn\",\"monthlyCost\":3.5}")));
        StepVerifier.create(handler.tryAction(new TryAction(YAML, "vm.get", "dev", json("{\"id\":\"vm-1\"}"))))
                .assertNext(r -> {
                    assertThat(r.ok()).isTrue();
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.response().path("name").asText()).isEqualTo("web");
                    assertThat(r.mismatches()).hasSize(1);
                    assertThat(r.mismatches().get(0).field()).isEqualTo("monthlyCost");
                    assertThat(r.mismatches().get(0).expected()).isEqualTo("missing");
                    assertThat(r.verified()).isNull();
                })
                .verifyComplete();
        ArgumentCaptor<ProviderCall> captor = ArgumentCaptor.forClass(ProviderCall.class);
        verify(calls).call(captor.capture());
        ProviderCall call = captor.getValue();
        assertThat(call.dryRun()).isFalse();
        assertThat(call.environment()).isEqualTo("dev");
        assertThat(call.action().id()).isEqualTo("vm.get");
        assertThat(call.provider().name()).isEqualTo("fleet");
        assertThat(call.jobId()).isNull();
    }

    @Test
    void aDeleteIsVerifiedThroughItsVerifyAction() {
        when(calls.call(argThat(c -> c != null && c.action().id().equals("vm.delete")))).thenReturn(Mono.just(json("{\"deleted\":true}")));
        when(calls.call(argThat(c -> c != null && c.action().id().equals("vm.get"))))
                .thenReturn(Mono.error(new ProviderCallException("not_found", "gone", false, 404)));
        StepVerifier.create(handler.tryAction(new TryAction(YAML, "vm.delete", "dev", json("{\"id\":\"vm-1\"}"))))
                .assertNext(r -> {
                    assertThat(r.ok()).isTrue();
                    assertThat(r.verified()).isTrue();
                    assertThat(r.mismatches()).isEmpty();
                })
                .verifyComplete();
    }

    @Test
    void aVerifyThatStillFindsTheThingIsNotVerified() {
        when(calls.call(any())).thenReturn(Mono.just(json("{\"id\":\"vm-1\"}")));
        StepVerifier.create(handler.tryAction(new TryAction(YAML, "vm.delete", "dev", json("{\"id\":\"vm-1\"}"))))
                .assertNext(r -> assertThat(r.verified()).isFalse())
                .verifyComplete();
    }

    @Test
    void aFailedCallIsAResultNotAnError() {
        when(calls.call(any())).thenReturn(Mono.error(new ProviderCallException("rate_limited", "slow down", true, 429)));
        StepVerifier.create(handler.tryAction(new TryAction(YAML, "vm.get", "prod", json("{\"id\":\"vm-1\"}"))))
                .assertNext(r -> {
                    assertThat(r.ok()).isFalse();
                    assertThat(r.status()).isEqualTo(429);
                    assertThat(r.response().path("error").path("class").asText()).isEqualTo("rate_limited");
                    assertThat(r.response().path("error").path("retryable").asBoolean()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    void anUnknownActionOrEnvironmentIsABadRequest() {
        StepVerifier.create(handler.tryAction(new TryAction(YAML, "vm.gett", "dev", null)))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("did you mean vm.get"))
                .verify();
        StepVerifier.create(handler.tryAction(new TryAction(YAML, "vm.get", "staging", null)))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("staging"))
                .verify();
        StepVerifier.create(handler.tryAction(new TryAction("kind: [", "vm.get", "dev", null))).expectError(IllegalArgumentException.class).verify();
    }

    // ---------------------------------------------------------------- validate

    @Test
    void validatesAFile() {
        StepVerifier.create(handler.validate(new Validate(YAML)))
                .assertNext(r -> {
                    assertThat(r.valid()).isTrue();
                    assertThat(r.checks()).hasSize(5);
                    assertThat(r.problems()).isEmpty();
                })
                .verifyComplete();
        StepVerifier.create(handler.validate(new Validate(YAML.replace("approval: required", "approval: none"))))
                .assertNext(r -> {
                    assertThat(r.valid()).isFalse();
                    assertThat(r.problems()).extracting(Problem::code).containsExactly("action.approvalRequired");
                })
                .verifyComplete();
    }

    // ---------------------------------------------------------------- publish

    @Test
    void publishingOpensAChangeRequestWithTheChecksAndTheDiff() {
        when(store.get("fleet")).thenReturn(Optional.of(stored(YAML_V1)));
        when(changeRequests.all()).thenReturn(List.of());
        StepVerifier.create(as(handler.publish(new Publish(YAML, null)), OWNER))
                .assertNext(accepted -> {
                    assertThat(accepted.id()).startsWith("cr_");
                    assertThat(accepted.status()).isEqualTo("open");
                    assertThat(accepted.links()).containsKey("changeRequest");
                })
                .verifyComplete();
        ArgumentCaptor<ChangeRequest> captor = ArgumentCaptor.forClass(ChangeRequest.class);
        verify(changeRequests).save(captor.capture());
        ChangeRequest cr = captor.getValue();
        assertThat(cr.kind()).isEqualTo(ChangeRequest.Kind.PROVIDER);
        assertThat(cr.name()).isEqualTo("fleet");
        assertThat(cr.version()).isEqualTo("1.1.0");
        assertThat(cr.branch()).isEqualTo("change/provider-fleet-1.1.0");
        assertThat(cr.title()).isEqualTo("Provider Fleet API 1.1.0");
        assertThat(cr.author()).isEqualTo("mateo");
        assertThat(cr.status()).isEqualTo(ChangeRequest.Status.OPEN);
        assertThat(cr.strategy()).isEqualTo("all");
        assertThat(cr.checks()).hasSize(5).allMatch(c -> "passed".equals(c.status()));
        assertThat(cr.yaml()).isEqualTo(YAML);
        assertThat(cr.diff()).startsWith("--- a/providers/team-compute/fleet.yaml\n+++ b/providers/team-compute/fleet.yaml\n");
        assertThat(cr.diff()).contains("-metadata: { name: fleet, title: Fleet API, owner: team-compute, version: 1.0.0 }");
        assertThat(cr.diff()).contains("+metadata: { name: fleet, title: Fleet API, owner: team-compute, version: 1.1.0 }");
        assertThat(cr.createdAt()).isEqualTo(NOW);
        assertThat(event("provider.change-requested")).isPresent();
        assertThat(event("provider.change-requested").orElseThrow().data().path("changeRequestId").asText()).isEqualTo(cr.id());
        verify(store, never()).save(any());
    }

    @Test
    void aNewProviderDiffsAgainstNothing() {
        when(store.get("fleet")).thenReturn(Optional.empty());
        when(changeRequests.all()).thenReturn(List.of());
        StepVerifier.create(as(handler.publish(new Publish(YAML, "canary")), OWNER)).expectNextCount(1).verifyComplete();
        ArgumentCaptor<ChangeRequest> captor = ArgumentCaptor.forClass(ChangeRequest.class);
        verify(changeRequests).save(captor.capture());
        assertThat(captor.getValue().diff()).startsWith("--- /dev/null\n");
        assertThat(captor.getValue().strategy()).isEqualTo("canary");
    }

    @Test
    void problemsBlockThePublishWithTheirDetail() {
        StepVerifier.create(as(handler.publish(new Publish(YAML.replace("credential: vault:fleet/token", "credential: hunter2"), null)), OWNER))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(ConflictException.class).hasMessageContaining("1 problem");
                    assertThat(((ConflictException) e).detail()).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST).hasSize(1);
                })
                .verify();
        verify(changeRequests, never()).save(any());
    }

    @Test
    void onlyTheOwnerTeamPublishes() {
        StepVerifier.create(as(handler.publish(new Publish(YAML, null)), OUTSIDER)).expectError(ForbiddenException.class).verify();
    }

    @Test
    void thePublishedVersionCannotBePublishedAgainAndOpenRequestsAreNotDuplicated() {
        when(store.get("fleet")).thenReturn(Optional.of(stored(YAML)));
        StepVerifier.create(as(handler.publish(new Publish(YAML, null)), OWNER))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(ConflictException.class).hasMessageContaining("1.1.0"))
                .verify();
        when(store.get("fleet")).thenReturn(Optional.of(stored(YAML_V1)));
        when(changeRequests.all()).thenReturn(List.of(new ChangeRequest("cr_x", ChangeRequest.Kind.PROVIDER, "fleet", "1.1.0", "b", "t", "mateo",
                ChangeRequest.Status.OPEN, List.of(), "all", YAML, "", NOW, null, null, null)));
        StepVerifier.create(as(handler.publish(new Publish(YAML, null)), OWNER))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(ConflictException.class).hasMessageContaining("already open"))
                .verify();
        StepVerifier.create(as(handler.publish(new Publish(YAML, "blue-green")), OWNER)).expectError(IllegalArgumentException.class).verify();
    }

    // ---------------------------------------------------------------- merged change requests

    @Test
    void aMergedProviderChangeRequestPublishesTheProvider() {
        ObjectNode data = JSON.createObjectNode();
        data.put("id", "cr_1");
        data.put("kind", "provider");
        data.put("name", "fleet");
        data.put("version", "1.1.0");
        data.put("yaml", YAML);
        data.put("by", "gonzalo");
        events.publish("change-request.merged", "change-request", "cr_1", "gonzalo", data);

        ArgumentCaptor<StoredProvider> saved = ArgumentCaptor.forClass(StoredProvider.class);
        verify(store).save(saved.capture());
        assertThat(saved.getValue().definition().name()).isEqualTo("fleet");
        assertThat(saved.getValue().definition().metadata().version()).isEqualTo("1.1.0");
        assertThat(saved.getValue().yaml()).isEqualTo(YAML);
        assertThat(saved.getValue().path()).isEqualTo("providers/team-compute/fleet.yaml");
        assertThat(saved.getValue().updatedBy()).isEqualTo("gonzalo");
        assertThat(saved.getValue().updatedAt()).isEqualTo(NOW);
        DomainEvent e = event("provider.published").orElseThrow();
        assertThat(e.aggregate()).isEqualTo("provider");
        assertThat(e.aggregateId()).isEqualTo("fleet");
        assertThat(e.actor()).isEqualTo("gonzalo");
        assertThat(e.data().path("name").asText()).isEqualTo("fleet");
        assertThat(e.data().path("version").asText()).isEqualTo("1.1.0");
        assertThat(e.data().path("by").asText()).isEqualTo("gonzalo");
    }

    @Test
    void aMergedEventWithoutTheFileLooksTheChangeRequestUp() {
        when(changeRequests.get("cr_2")).thenReturn(Optional.of(new ChangeRequest("cr_2", ChangeRequest.Kind.PROVIDER, "fleet", "1.1.0", "b", "t", "mateo",
                ChangeRequest.Status.MERGED, List.of(), "all", YAML, "", NOW, NOW, "gonzalo", null)));
        ObjectNode data = JSON.createObjectNode();
        data.put("id", "cr_2");
        data.put("kind", "provider");
        events.publish("change-request.merged", "change-request", "cr_2", "gonzalo", data);
        verify(store).save(any());
        assertThat(event("provider.published")).isPresent();
    }

    @Test
    void mergedWorkflowChangeRequestsAreIgnored() {
        ObjectNode data = JSON.createObjectNode();
        data.put("id", "cr_3");
        data.put("kind", "workflow");
        data.put("yaml", "kind: Workflow");
        events.publish("change-request.merged", "change-request", "cr_3", "gonzalo", data);
        events.publish("change-request.closed", "change-request", "cr_4", "gonzalo", data);
        verify(store, never()).save(any());
        assertThat(event("provider.published")).isEmpty();
    }

    // ---------------------------------------------------------------- overrides and slo

    @Test
    void anOverrideMergesTheInferredSchemaOverTheOutput() {
        when(store.get("fleet")).thenReturn(Optional.of(stored(YAML)));
        StepVerifier.create(as(handler.overrides("fleet", new SetOverride("vm.get", json("{\"id\":\"vm-1\",\"powerState\":\"poweredOn\",\"cpu\":4}"))), OWNER))
                .assertNext(a -> {
                    assertThat(a.id()).isEqualTo("fleet");
                    assertThat(a.status()).isEqualTo("updated");
                })
                .verifyComplete();
        ArgumentCaptor<StoredProvider> saved = ArgumentCaptor.forClass(StoredProvider.class);
        verify(store).save(saved.capture());
        JsonNode response = saved.getValue().definition().override("vm.get").get("response");
        assertThat(response.path("type").asText()).isEqualTo("object");
        assertThat(response.path("properties").path("cpu").path("type").asText()).isEqualTo("integer");
        assertThat(response.path("properties").path("name").path("type").asText()).isEqualTo("string");
        assertThat(response.path("properties").path("powerState").path("enum")).hasSize(2);
        assertThat(saved.getValue().yaml()).contains("overrides:");
        assertThat(saved.getValue().updatedBy()).isEqualTo("mateo");
        assertThat(event("provider.updated").orElseThrow().data().path("change").asText()).isEqualTo("overrides");
    }

    @Test
    void overridesAreGuarded() {
        when(store.get("fleet")).thenReturn(Optional.of(stored(YAML)));
        when(store.get("nope")).thenReturn(Optional.empty());
        StepVerifier.create(as(handler.overrides("nope", new SetOverride("vm.get", json("{}"))), OWNER)).expectError(NotFoundException.class).verify();
        StepVerifier.create(as(handler.overrides("fleet", new SetOverride("vm.get", json("{}"))), OUTSIDER)).expectError(ForbiddenException.class).verify();
        StepVerifier.create(as(handler.overrides("fleet", new SetOverride("vm.nope", json("{}"))), OWNER)).expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(as(handler.overrides("fleet", new SetOverride("vm.get", null)), OWNER)).expectError(IllegalArgumentException.class).verify();
        verify(store, never()).save(any());
    }

    @Test
    void theSloAndTheAlertChannelCanBeChanged() {
        when(store.get("fleet")).thenReturn(Optional.of(stored(YAML)));
        StepVerifier.create(as(handler.slo("fleet", new SetSlo(99.9, 500, "#fleet-alerts")), OWNER)).expectNextCount(1).verifyComplete();
        ArgumentCaptor<StoredProvider> saved = ArgumentCaptor.forClass(StoredProvider.class);
        verify(store).save(saved.capture());
        assertThat(saved.getValue().definition().slo().successRate()).isEqualTo(99.9);
        assertThat(saved.getValue().definition().slo().p95Ms()).isEqualTo(500);
        assertThat(saved.getValue().definition().spec().alerts().channel()).isEqualTo("#fleet-alerts");
        assertThat(saved.getValue().definition().actions()).hasSize(2);
        StepVerifier.create(as(handler.slo("fleet", new SetSlo(120.0, null, null)), OWNER)).expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(as(handler.slo("fleet", new SetSlo(null, 0, null)), OWNER)).expectError(IllegalArgumentException.class).verify();
    }

    @Test
    void aPartialSloKeepsTheOtherValues() {
        when(store.get("fleet")).thenReturn(Optional.of(stored(YAML)));
        StepVerifier.create(as(handler.slo("fleet", new SetSlo(null, 650, null)), OWNER)).expectNextCount(1).verifyComplete();
        ArgumentCaptor<StoredProvider> saved = ArgumentCaptor.forClass(StoredProvider.class);
        verify(store).save(saved.capture());
        assertThat(saved.getValue().definition().slo().successRate()).isEqualTo(99.5);
        assertThat(saved.getValue().definition().slo().p95Ms()).isEqualTo(650);
        assertThat(saved.getValue().definition().spec().alerts().channel()).isEqualTo("#compute-alerts");
    }

    // ---------------------------------------------------------------- draft

    @Test
    void draftingAsksTheAssistant() {
        ProviderDraft draft = new ProviderDraft("kind: Provider", "A fleet provider", List.of("bearer auth"), List.of());
        when(assistant.draftProvider("the fleet API at https://fleet.example.com")).thenReturn(Mono.just(draft));
        StepVerifier.create(handler.draft(new Draft("  the fleet API at https://fleet.example.com "))).expectNext(draft).verifyComplete();
        StepVerifier.create(handler.draft(new Draft(" "))).expectError(IllegalArgumentException.class).verify();
    }
}
