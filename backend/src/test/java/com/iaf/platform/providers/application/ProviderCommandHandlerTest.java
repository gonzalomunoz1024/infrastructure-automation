package com.iaf.platform.providers.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.common.ports.CredentialsPort.CredentialInfo;
import com.iaf.platform.common.redaction.SecretValues;
import com.iaf.platform.identity.application.CurrentIdentity;
import com.iaf.platform.providers.adapters.outbound.InMemoryReviewEvidence;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.providers.domain.ProviderDefinition.GitRef;
import com.iaf.platform.providers.domain.ProviderYaml;
import com.iaf.platform.providers.domain.ReviewEvidence.ActionTry;
import com.iaf.platform.providers.domain.ReviewEvidence.ConnectionTest;
import com.iaf.platform.providers.ports.inbound.ProviderCommands.Draft;
import com.iaf.platform.providers.ports.inbound.ProviderCommands.ImportSpec;
import com.iaf.platform.providers.ports.inbound.ProviderCommands.Publish;
import com.iaf.platform.providers.ports.inbound.ProviderCommands.SetOverride;
import com.iaf.platform.providers.ports.inbound.ProviderCommands.SetSlo;
import com.iaf.platform.providers.ports.inbound.ProviderCommands.TestConnection;
import com.iaf.platform.providers.ports.inbound.ProviderCommands.TryAction;
import com.iaf.platform.providers.ports.inbound.ProviderCommands.Validate;
import com.iaf.platform.providers.ports.outbound.GitSourcePort;
import com.iaf.platform.providers.ports.outbound.GitSourcePort.GitFile;
import com.iaf.platform.providers.ports.outbound.HttpProbePort;
import com.iaf.platform.providers.ports.outbound.HttpProbePort.ProbeResult;
import com.iaf.platform.providers.ports.outbound.ProviderStorePort;
import com.iaf.platform.providers.ports.outbound.ProviderStorePort.StoredProvider;
import com.iaf.platform.releases.ports.inbound.ReleaseQueries;
import com.iaf.platform.runs.ports.outbound.ProviderCallPort;
import com.iaf.platform.runs.ports.outbound.ProviderCallPort.ProviderCall;
import com.iaf.platform.runs.ports.outbound.ProviderCallPort.ProviderCallException;
import com.iaf.platform.workflows.domain.ChangeRequest;
import com.iaf.platform.workflows.domain.ChangeRequest.Check;
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
                  plan: vm.get
                  verify: { action: vm.get, expect: notFound }
              errors:
                - { match: { status: 404 }, class: not_found, retry: false }
              slo: { successRate: 99.5, p95Ms: 800 }
              alerts: { channel: "#compute-alerts" }
            """;
    static final String YAML_V1 = YAML.replace("version: 1.1.0", "version: 1.0.0");
    /** The same provider with its spec in a private repository. */
    static final String YAML_FROM_GIT = YAML.replace("spec: { url: https://fleet.example.com/openapi.json, ",
            "spec: { git: { repo: https://github.com/acme/fleet-api, ref: main, path: openapi.yaml, credential: vault:github/read-token }, commit: 3f2a9c1d, ");
    static final String ANSIBLE = """
            apiVersion: iaf.platform/v1
            kind: Provider
            metadata: { name: ops-playbooks, owner: team-compute, version: 1.0.0 }
            spec:
              type: ansible
              source: { git: { repo: https://github.com/acme/ops-playbooks, ref: v3.1.0, credential: vault:github/read-token }, commit: 4e1d9a2c }
              auth: { type: none, credential: vault:ssh/automation }
              actions: [{ id: vm.restart, playbook: vm/restart.yml, effect: change }]
            """;

    ProviderStorePort store;
    ChangeRequestPort changeRequests;
    ProviderCallPort calls;
    AssistantCommands assistant;
    HttpProbePort http;
    EventBus events;
    List<DomainEvent> published;
    InMemoryReviewEvidence evidence;
    GitSourcePort gitSource;
    ReleaseQueries releases;
    CredentialsPort credentials;
    SecretValues secrets;
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
        evidence = new InMemoryReviewEvidence();
        gitSource = mock(GitSourcePort.class);
        releases = mock(ReleaseQueries.class);
        when(releases.providerCandidate(anyString())).thenReturn(Mono.empty());
        credentials = mock(CredentialsPort.class);
        when(credentials.info(any())).thenReturn(Mono.empty());
        secrets = new SecretValues();
        handler = new ProviderCommandHandler(store, changeRequests, calls, assistant, http, events, CLOCK, evidence, gitSource, releases, credentials, secrets);
        handler.listen();
    }

    static <T> Mono<T> as(Mono<T> mono, Identity who) {
        return mono.contextWrite(ctx -> CurrentIdentity.with(ctx, who));
    }

    /** The credential store knows {@code reference} as owned by {@code team} (null: anyone may use it). */
    void owned(String reference, String team) {
        when(credentials.info(reference)).thenReturn(Mono.just(new CredentialInfo(reference.substring("vault:".length()), "token", reference, team, NOW)));
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

    /** What the wizard would have proved for the fleet provider: dev answers and both actions were tried there. */
    void proven() {
        evidence.recordConnection("fleet", new ConnectionTest("dev", "https://fleet-dev.example.com", "vault:fleet/token", true, 12, "HEAD 200 in 12 ms", NOW, "mateo"));
        evidence.recordTry("fleet", new ActionTry("vm.get", "dev", true, 200, 120, null,
                json("{\"id\":\"vm-1\",\"name\":\"web\",\"powerState\":\"poweredOn\"}"), null, NOW, "mateo"));
        evidence.recordTry("fleet", new ActionTry("vm.delete", "dev", true, 200, 300, null, json("{}"), true, NOW, "mateo"));
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

    @Test
    void importsASpecFromGitWithTheCommitItCameFrom() {
        when(gitSource.file("https://github.com/acme/machines-api", "v2.4.0", "api/openapi.yaml", "vault:github/read-token"))
                .thenReturn(Mono.just(new GitFile("api/openapi.yaml", spec(), "3f2a9c1d0e4b5a6c7d8e9f0a1b2c3d4e5f6a7b8c")));
        GitRef git = new GitRef(" https://github.com/acme/machines-api ", "v2.4.0", "api/openapi.yaml", "vault:github/read-token");
        StepVerifier.create(handler.importSpec(new ImportSpec("openapi", null, null, git)))
                .assertNext(s -> {
                    assertThat(s.title()).isEqualTo("Machines API");
                    assertThat(s.commit()).isEqualTo("3f2a9c1d0e4b5a6c7d8e9f0a1b2c3d4e5f6a7b8c");
                    assertThat(s.sha256()).hasSize(64);
                })
                .verifyComplete();
    }

    @Test
    void aGitImportNamesTheFileAndTakesOnlyAVaultReferenceForTheToken() {
        StepVerifier.create(handler.importSpec(new ImportSpec("openapi", null, null, new GitRef("https://github.com/acme/x", "main", " ", null))))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("git.path is required"))
                .verify();
        StepVerifier.create(handler.importSpec(new ImportSpec("openapi", null, null, new GitRef("https://github.com/acme/x", "main", "openapi.yaml", "ghp_s3cr3t"))))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Vault reference")
                        .hasMessageNotContaining("ghp_s3cr3t"))
                .verify();
        StepVerifier.create(handler.importSpec(new ImportSpec("openapi", "https://x.example.com/spec", null, new GitRef("https://github.com/acme/x", "main", "openapi.yaml", null))))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("one of them"))
                .verify();
        verify(gitSource, never()).file(any(), any(), any(), any());
    }

    @Test
    void aRepositoryThatCannotBeReadIsABadRequest() {
        when(gitSource.file(any(), any(), any(), any())).thenReturn(Mono.error(new IllegalStateException("connection reset")));
        StepVerifier.create(handler.importSpec(new ImportSpec("openapi", null, null, new GitRef("https://github.com/acme/x", null, "openapi.yaml", null))))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("Could not read openapi.yaml from https://github.com/acme/x: connection reset"))
                .verify();
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

    @Test
    void aConnectionTestForANamedProviderIsKeptAsReviewEvidence() {
        when(http.probe(eq("https://fleet-dev.example.com"), any())).thenReturn(Mono.just(new ProbeResult(true, 12, "HEAD 200 in 12 ms")));
        when(http.probe(eq("https://fleet-test.example.com"), any())).thenReturn(Mono.just(new ProbeResult(false, 30, "HEAD 401")));
        Map<String, String> endpoints = new LinkedHashMap<>();
        endpoints.put("dev", "https://fleet-dev.example.com ");
        endpoints.put("test", "https://fleet-test.example.com");
        StepVerifier.create(as(handler.testConnection(new TestConnection(endpoints, json("{\"type\":\"bearer\",\"credential\":\"vault:fleet/token\"}"), "fleet")), OWNER))
                .expectNextCount(1)
                .verifyComplete();
        List<ConnectionTest> recorded = evidence.evidence("fleet").connections();
        assertThat(recorded).extracting(ConnectionTest::environment).containsExactly("dev", "test");
        assertThat(recorded.get(0).endpoint()).isEqualTo("https://fleet-dev.example.com");
        assertThat(recorded.get(0).credential()).isEqualTo("vault:fleet/token");
        assertThat(recorded.get(0).ok()).isTrue();
        assertThat(recorded.get(1).ok()).isFalse();
        assertThat(recorded.get(1).message()).isEqualTo("HEAD 401");
        assertThat(recorded).allMatch(c -> "mateo".equals(c.by()) && NOW.equals(c.at()));
        // without a provider name nothing is recorded, and a bad name is refused
        StepVerifier.create(handler.testConnection(new TestConnection(endpoints, null))).expectNextCount(1).verifyComplete();
        assertThat(evidence.evidence("fleet").connections()).hasSize(2);
        StepVerifier.create(handler.testConnection(new TestConnection(endpoints, null, "Fleet API"))).expectError(IllegalArgumentException.class).verify();
    }

    // ---------------------------------------------------------------- try it

    @Test
    void aTryThatAnswersSavesTheResponseAsTheActionsSampleInTheDraft() {
        when(calls.call(any())).thenReturn(Mono.just(json("{\"id\":\"vm-1\",\"name\":\"web\",\"powerState\":\"poweredOn\",\"apiToken\":\"s3cr3t\"}")));
        StepVerifier.create(as(handler.tryAction(new TryAction(YAML, "vm.get", "dev", json("{\"id\":\"vm-1\"}"))), OWNER))
                .assertNext(r -> {
                    assertThat(r.ok()).isTrue();
                    assertThat(r.environment()).isEqualTo("dev");
                    assertThat(r.at()).isEqualTo(NOW);
                    var action = ProviderYaml.parse(r.yaml()).action("vm.get").orElseThrow();
                    assertThat(action.sample().path("name").asText()).isEqualTo("web");
                    assertThat(action.sample().path("apiToken").asText()).as("secrets stay out of the file").isEqualTo("vault:redacted");
                    assertThat(action.recorded().path("environment").asText()).isEqualTo("dev");
                    assertThat(action.recorded().path("at").asText()).isEqualTo("2026-10-17T10:00:00Z");
                    assertThat(r.yaml()).doesNotContain("s3cr3t");
                    assertThat(r.response().path("apiToken").asText()).as("the caller still sees the real answer").isEqualTo("s3cr3t");
                })
                .verifyComplete();
        ActionTry t = evidence.evidence("fleet").tries().get(0);
        assertThat(t.action()).isEqualTo("vm.get");
        assertThat(t.environment()).isEqualTo("dev");
        assertThat(t.ok()).isTrue();
        assertThat(t.response().path("name").asText()).isEqualTo("web");
        assertThat(t.response().path("apiToken").asText()).as("the evidence copy is redacted too").isEqualTo("vault:redacted");
        assertThat(t.response().toString()).doesNotContain("s3cr3t");
        assertThat(t.by()).isEqualTo("mateo");
    }

    @Test
    void aSecretThePlatformResolvedIsRedactedFromTheEvidenceWhereverTheAnswerEchoesIt() {
        secrets.remember("tok-3n-value");
        when(calls.call(any())).thenReturn(Mono.just(json("{\"id\":\"vm-1\",\"note\":\"Bearer tok-3n-value\",\"credential\":\"vault:fleet/token\","
                + "\"session\":\"eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhbmEifQ.c2lnbmF0dXJlLXBhcnQ\"}")));
        StepVerifier.create(as(handler.tryAction(new TryAction(YAML, "vm.get", "dev", json("{\"id\":\"vm-1\"}"))), OWNER))
                .assertNext(r -> {
                    assertThat(r.response().path("note").asText()).as("the caller still sees the real answer").isEqualTo("Bearer tok-3n-value");
                    var sample = ProviderYaml.parse(r.yaml()).action("vm.get").orElseThrow().sample();
                    assertThat(sample.path("note").asText()).isEqualTo("Bearer vault:redacted");
                    assertThat(sample.path("session").asText()).as("token shapes are masked too").isEqualTo("vault:redacted");
                    assertThat(sample.path("credential").asText()).as("a reference names a secret without holding it").isEqualTo("vault:fleet/token");
                })
                .verifyComplete();
        ActionTry t = evidence.evidence("fleet").tries().get(0);
        assertThat(t.response().path("note").asText()).isEqualTo("Bearer vault:redacted");
        assertThat(t.response().path("session").asText()).isEqualTo("vault:redacted");
        assertThat(t.response().path("credential").asText()).isEqualTo("vault:fleet/token");
        assertThat(t.response().toString()).doesNotContain("tok-3n-value");
    }

    @Test
    void aFailedTryIsEvidenceWithoutASample() {
        when(calls.call(any())).thenReturn(Mono.error(new ProviderCallException("not_found", "no such vm", false, 404)));
        StepVerifier.create(as(handler.tryAction(new TryAction(YAML, "vm.get", "dev", json("{\"id\":\"vm-9\"}"))), OWNER))
                .assertNext(r -> {
                    assertThat(r.ok()).isFalse();
                    assertThat(r.yaml()).isNull();
                    assertThat(r.environment()).isEqualTo("dev");
                })
                .verifyComplete();
        ActionTry t = evidence.evidence("fleet").tries().get(0);
        assertThat(t.ok()).isFalse();
        assertThat(t.status()).isEqualTo(404);
        assertThat(t.errorClass()).isEqualTo("not_found");
        assertThat(t.response()).isNull();
    }

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

    // ---------------------------------------------------------------- who may send a credential outward

    @Test
    void aCredentialAnotherTeamOwnsNeverLeavesTheWizard() {
        owned("vault:fleet/token", "team-data");
        Map<String, String> endpoints = Map.of("dev", "https://fleet-dev.example.com");
        JsonNode auth = json("{\"type\":\"bearer\",\"credential\":\"vault:fleet/token\"}");
        StepVerifier.create(as(handler.testConnection(new TestConnection(endpoints, auth, "fleet")), OWNER))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(ForbiddenException.class)
                        .hasMessage("Mateo may not use credential vault:fleet/token (owned by team-data)"))
                .verify();
        StepVerifier.create(as(handler.tryAction(new TryAction(YAML, "vm.get", "dev", json("{\"id\":\"vm-1\"}"))), OWNER))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(ForbiddenException.class).hasMessageContaining("vault:fleet/token"))
                .verify();
        verify(http, never()).probe(any(), any());
        verify(calls, never()).call(any());
        verify(credentials, never()).resolve(any());
        assertThat(evidence.evidence("fleet").connections()).isEmpty();
        assertThat(evidence.evidence("fleet").tries()).isEmpty();
    }

    @Test
    void theOwnerTeamAnAdminAndAnyoneWithAnUnownedCredentialMayTestAndTry() {
        when(http.probe(any(), any())).thenReturn(Mono.just(new ProbeResult(true, 12, "HEAD 200 in 12 ms")));
        when(calls.call(any())).thenReturn(Mono.just(json("{\"id\":\"vm-1\",\"name\":\"web\",\"powerState\":\"poweredOn\"}")));
        Map<String, String> endpoints = Map.of("dev", "https://fleet-dev.example.com");
        JsonNode auth = json("{\"type\":\"bearer\",\"credential\":\"vault:fleet/token\"}");
        Identity admin = new Identity("root", "Root", null, List.of(), List.of("admin"), true, Identity.Kind.USER);
        owned("vault:fleet/token", "team-compute");
        StepVerifier.create(as(handler.testConnection(new TestConnection(endpoints, auth)), OWNER)).assertNext(r -> assertThat(r.allOk()).isTrue()).verifyComplete();
        StepVerifier.create(as(handler.tryAction(new TryAction(YAML, "vm.get", "dev", null)), OWNER)).assertNext(r -> assertThat(r.ok()).isTrue()).verifyComplete();
        owned("vault:fleet/token", "team-data");
        StepVerifier.create(as(handler.testConnection(new TestConnection(endpoints, auth)), admin)).expectNextCount(1).verifyComplete();
        StepVerifier.create(as(handler.tryAction(new TryAction(YAML, "vm.get", "dev", null)), admin)).expectNextCount(1).verifyComplete();
        owned("vault:fleet/token", null);
        StepVerifier.create(as(handler.testConnection(new TestConnection(endpoints, auth)), OUTSIDER)).expectNextCount(1).verifyComplete();
        StepVerifier.create(as(handler.tryAction(new TryAction(YAML, "vm.get", "dev", null)), OUTSIDER)).expectNextCount(1).verifyComplete();
        // a reference the store does not know is judged where it is resolved, as before
        when(credentials.info("vault:fleet/token")).thenReturn(Mono.empty());
        StepVerifier.create(as(handler.testConnection(new TestConnection(endpoints, auth)), OUTSIDER)).expectNextCount(1).verifyComplete();
        StepVerifier.create(as(handler.tryAction(new TryAction(YAML, "vm.get", "dev", null)), OUTSIDER)).expectNextCount(1).verifyComplete();
    }

    @Test
    void aSpecImportFromGitChecksTheReadTokenBeforeReadingTheRepository() {
        owned("vault:github/read-token", "team-data");
        GitRef git = new GitRef("https://github.com/acme/machines-api", "v2.4.0", "api/openapi.yaml", "vault:github/read-token");
        StepVerifier.create(as(handler.importSpec(new ImportSpec("openapi", null, null, git)), OWNER))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(ForbiddenException.class)
                        .hasMessageContaining("vault:github/read-token").hasMessageContaining("team-data"))
                .verify();
        verify(gitSource, never()).file(any(), any(), any(), any());
        when(gitSource.file(any(), any(), any(), any())).thenReturn(Mono.just(new GitFile("api/openapi.yaml", spec(), "3f2a9c1d0e4b5a6c7d8e9f0a1b2c3d4e5f6a7b8c")));
        StepVerifier.create(as(handler.importSpec(new ImportSpec("openapi", null, null, git)), OUTSIDER)).expectNextCount(1).verifyComplete();
    }

    @Test
    void aTryChecksEveryReferenceTheCallWouldResolve() {
        assertThat(ProviderCommandHandler.credentialsOf(ProviderYaml.parse(YAML))).containsExactly("vault:fleet/token");
        assertThat(ProviderCommandHandler.credentialsOf(ProviderYaml.parse(YAML_FROM_GIT))).containsExactly("vault:fleet/token", "vault:github/read-token");
        assertThat(ProviderCommandHandler.credentialsOf(ProviderYaml.parse(ANSIBLE))).containsExactly("vault:ssh/automation", "vault:github/read-token");
        owned("vault:github/read-token", "team-data");
        StepVerifier.create(as(handler.tryAction(new TryAction(YAML_FROM_GIT, "vm.get", "dev", null)), OWNER))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(ForbiddenException.class).hasMessageContaining("vault:github/read-token"))
                .verify();
        verify(calls, never()).call(any());
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

    @Test
    void validationCarriesTheReviewNextToTheFileChecks() {
        StepVerifier.create(handler.validate(new Validate(YAML)))
                .assertNext(r -> {
                    assertThat(r.valid()).as("the file itself is fine").isTrue();
                    assertThat(r.review().ready()).isFalse();
                    assertThat(r.review().errors()).extracting(Problem::code)
                            .containsExactly("connection.untested", "action.untried", "action.untried");
                    assertThat(r.review().slo()).isNull();
                })
                .verifyComplete();
        proven();
        StepVerifier.create(handler.validate(new Validate(YAML)))
                .assertNext(r -> {
                    assertThat(r.review().ready()).isTrue();
                    assertThat(r.review().slo().p95Ms()).isEqualTo(450);
                })
                .verifyComplete();
        StepVerifier.create(handler.validate(new Validate("kind: [")))
                .assertNext(r -> assertThat(r.review()).as("no review of a file that is not a provider").isNull())
                .verifyComplete();
    }

    @Test
    void publishingWithoutProofIsRefusedWithTheReviewProblems() {
        when(store.get("fleet")).thenReturn(Optional.of(stored(YAML_V1)));
        when(changeRequests.all()).thenReturn(List.of());
        StepVerifier.create(as(handler.publish(new Publish(YAML, null)), OWNER))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(ConflictException.class).hasMessage("The provider's review has 3 problems to fix before it can be published");
                    assertThat(((ConflictException) e).detail()).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(Problem.class))
                            .extracting(Problem::code).containsExactly("connection.untested", "action.untried", "action.untried");
                })
                .verify();
        verify(changeRequests, never()).save(any());
    }

    // ---------------------------------------------------------------- publish

    @Test
    void publishingOpensAChangeRequestWithTheChecksAndTheDiff() {
        when(store.get("fleet")).thenReturn(Optional.of(stored(YAML_V1)));
        when(changeRequests.all()).thenReturn(List.of());
        proven();
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
        assertThat(cr.strategy()).as("like a workflow version, through a canary unless the publish says all").isEqualTo("canary");
        assertThat(cr.checks()).extracting(Check::id).containsExactly("schema", "spec-pinned", "credentials", "actions", "slo",
                "connection", "tried", "errors", "preview", "responses", "slo-suggested");
        assertThat(cr.checks()).allMatch(c -> "passed".equals(c.status()));
        assertThat(cr.checks()).filteredOn(c -> c.id().equals("tried")).extracting(Check::detail).containsExactly("2 of 2 actions answered in dev");
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
        proven();
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

    void merge(String id, String strategy) {
        when(changeRequests.get(id)).thenReturn(Optional.of(new ChangeRequest(id, ChangeRequest.Kind.PROVIDER, "fleet", "1.1.0", "b", "t", "mateo",
                ChangeRequest.Status.MERGED, List.of(), strategy, YAML, "", NOW, NOW, "gonzalo", null)));
        ObjectNode data = JSON.createObjectNode();
        data.put("id", id);
        data.put("kind", "provider");
        data.put("yaml", YAML);
        data.put("by", "gonzalo");
        events.publish("change-request.merged", "change-request", id, "gonzalo", data);
    }

    @Test
    void aMergedCanaryVersionIsKeptBesideTheCurrentOneForTheReleaseToRollOut() {
        when(store.get("fleet")).thenReturn(Optional.of(stored(YAML_V1)));
        merge("cr_5", "canary");

        ArgumentCaptor<StoredProvider> kept = ArgumentCaptor.forClass(StoredProvider.class);
        verify(store).saveVersion(kept.capture());
        verify(store, never()).save(any());
        assertThat(kept.getValue().definition().metadata().version()).isEqualTo("1.1.0");
        JsonNode published = event("provider.published").orElseThrow().data();
        assertThat(published.path("version").asText()).isEqualTo("1.1.0");
        assertThat(published.path("previous").asText()).isEqualTo("1.0.0");
        assertThat(published.path("strategy").asText()).isEqualTo("canary");
    }

    @Test
    void aMergedVersionForEveryCallOrAFirstVersionBecomesCurrentAtOnce() {
        when(store.get("fleet")).thenReturn(Optional.of(stored(YAML_V1)));
        merge("cr_6", "all");
        verify(store).save(argThat(p -> p.definition().metadata().version().equals("1.1.0")));
        verify(store, never()).saveVersion(any());
        assertThat(event("provider.published").orElseThrow().data().path("strategy").asText()).isEqualTo("all");

        published.clear();
        when(store.get("fleet")).thenReturn(Optional.empty());
        merge("cr_7", "canary");
        verify(store, never()).saveVersion(any());
        assertThat(event("provider.published").orElseThrow().data().path("previous").isNull()).isTrue();
    }

    ObjectNode release(String provider, String from, String to) {
        ObjectNode data = JSON.createObjectNode();
        data.put("releaseId", "rel_1");
        if (provider != null) {
            data.put("provider", provider);
        } else {
            data.put("workflow", "vm-restart");
        }
        data.put("from", from);
        data.put("to", to);
        return data;
    }

    @Test
    void aPromotedProviderReleaseMakesItsCandidateTheCurrentVersion() {
        when(store.get("fleet")).thenReturn(Optional.of(stored(YAML_V1)));
        when(store.version("fleet", "1.1.0")).thenReturn(Optional.of(stored(YAML)));
        events.publish("release.promoted", "release", "rel_1", "system", release("fleet", "1.0.0", "1.1.0"));
        verify(store).save(argThat(p -> p.definition().metadata().version().equals("1.1.0") && p.yaml().equals(YAML)));
    }

    @Test
    void aPromotionChangesNothingWhenAnotherVersionWasMadeCurrentSinceOrForAWorkflow() {
        when(store.get("fleet")).thenReturn(Optional.of(stored(YAML.replace("version: 1.1.0", "version: 1.2.0"))));
        when(store.version("fleet", "1.1.0")).thenReturn(Optional.of(stored(YAML)));
        events.publish("release.promoted", "release", "rel_1", "system", release("fleet", "1.0.0", "1.1.0"));
        events.publish("release.promoted", "release", "rel_2", "system", release("fleet", "1.1.0", "1.2.0"));
        events.publish("release.promoted", "release", "rel_3", "system", release(null, "1.0.0", "1.1.0"));
        events.publish("release.rolled-back", "release", "rel_4", "system", release("fleet", "1.2.0", "1.3.0"));
        verify(store, never()).save(any());
    }

    // ---------------------------------------------------------------- overrides and slo

    ChangeRequest opened() {
        ArgumentCaptor<ChangeRequest> cr = ArgumentCaptor.forClass(ChangeRequest.class);
        verify(changeRequests).save(cr.capture());
        return cr.getValue();
    }

    static ChangeRequest openProviderChange(String version) {
        return new ChangeRequest("cr_open", ChangeRequest.Kind.PROVIDER, "fleet", version, "b", "t", "ana", ChangeRequest.Status.OPEN, List.of(), "all", YAML, "",
                NOW, null, null, null);
    }

    @Test
    void anOverrideOpensAChangeRequestForTheNextPatchVersion() {
        when(store.get("fleet")).thenReturn(Optional.of(stored(YAML)));
        StepVerifier.create(as(handler.overrides("fleet", new SetOverride("vm.get", json("{\"id\":\"vm-1\",\"powerState\":\"poweredOn\",\"cpu\":4}"))), OWNER))
                .assertNext(a -> {
                    assertThat(a.status()).isEqualTo("open");
                    assertThat(a.links()).containsEntry("changeRequest", "/api/v1/change-requests/" + a.id()).containsEntry("provider", "/api/v1/providers/fleet");
                })
                .verifyComplete();
        verify(store, never()).save(any());
        ChangeRequest cr = opened();
        assertThat(cr.kind()).isEqualTo(ChangeRequest.Kind.PROVIDER);
        assertThat(cr.version()).isEqualTo("1.1.1");
        assertThat(cr.title()).isEqualTo("Provider Fleet API 1.1.1: vm.get uses the real response");
        assertThat(cr.author()).isEqualTo("mateo");
        assertThat(cr.diff()).contains("--- a/providers/team-compute/fleet.yaml").contains("overrides:");
        JsonNode response = ProviderYaml.parse(cr.yaml()).override("vm.get").get("response");
        assertThat(response.path("type").asText()).isEqualTo("object");
        assertThat(response.path("properties").path("cpu").path("type").asText()).isEqualTo("integer");
        assertThat(response.path("properties").path("name").path("type").asText()).isEqualTo("string");
        assertThat(response.path("properties").path("powerState").path("enum")).hasSize(2);
        assertThat(cr.checks()).extracting(Check::id).containsExactly("schema", "spec-pinned", "credentials", "actions", "slo", "responses");
        assertThat(event("provider.change-requested").orElseThrow().data().path("change").asText()).isEqualTo("overrides");
    }

    @Test
    void anOverrideThatCoversTheRealResponsePassesTheResponsesCheck() {
        when(store.get("fleet")).thenReturn(Optional.of(stored(YAML)));
        JsonNode real = json("{\"id\":\"vm-1\",\"name\":\"web\",\"powerState\":\"poweredOn\",\"monthlyCost\":3.5}");
        evidence.recordTry("fleet", new ActionTry("vm.get", "dev", true, 200, 120, null, real, null, NOW, "mateo"));
        StepVerifier.create(as(handler.overrides("fleet", new SetOverride("vm.get", real)), OWNER)).expectNextCount(1).verifyComplete();
        Check responses = opened().checks().stream().filter(c -> c.id().equals("responses")).findFirst().orElseThrow();
        assertThat(responses.status()).isEqualTo("passed");
        assertThat(responses.detail()).isEqualTo("1 of 1 response matches");
    }

    @Test
    void overridesAreGuarded() {
        when(store.get("fleet")).thenReturn(Optional.of(stored(YAML)));
        when(store.get("nope")).thenReturn(Optional.empty());
        StepVerifier.create(as(handler.overrides("nope", new SetOverride("vm.get", json("{}"))), OWNER)).expectError(NotFoundException.class).verify();
        StepVerifier.create(as(handler.overrides("fleet", new SetOverride("vm.get", json("{}"))), OUTSIDER)).expectError(ForbiddenException.class).verify();
        StepVerifier.create(as(handler.overrides("fleet", new SetOverride("vm.nope", json("{}"))), OWNER)).expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(as(handler.overrides("fleet", new SetOverride("vm.get", null)), OWNER)).expectError(IllegalArgumentException.class).verify();
        when(changeRequests.all()).thenReturn(List.of(openProviderChange("1.2.0")));
        StepVerifier.create(as(handler.overrides("fleet", new SetOverride("vm.get", json("{\"cpu\":4}"))), OWNER))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(ConflictException.class)
                        .hasMessage("Change request cr_open for provider fleet 1.2.0 is still open; merge or close it first"))
                .verify();
        verify(store, never()).save(any());
        verify(changeRequests, never()).save(any());
    }

    @Test
    void anOverrideTheSchemaAlreadyHasChangesNothing() {
        String withOverride = YAML.replace("\n  errors:", "\n  overrides:\n    vm.get: { response: { type: object, properties: { id: { type: string }, "
                + "name: { type: string }, powerState: { type: string, enum: [poweredOn, poweredOff] } } } }\n  errors:");
        assertThat(withOverride).contains("overrides:");
        when(store.get("fleet")).thenReturn(Optional.of(stored(withOverride)));
        StepVerifier.create(as(handler.overrides("fleet", new SetOverride("vm.get", json("{\"id\":\"vm-1\",\"name\":\"web\"}"))), OWNER))
                .assertNext(a -> assertThat(a.status()).isEqualTo("unchanged"))
                .verifyComplete();
        verify(changeRequests, never()).save(any());
    }

    @Test
    void theSloAndTheAlertChannelChangeThroughAChangeRequest() {
        when(store.get("fleet")).thenReturn(Optional.of(stored(YAML)));
        StepVerifier.create(as(handler.slo("fleet", new SetSlo(99.9, 500, "#fleet-alerts")), OWNER))
                .assertNext(a -> assertThat(a.status()).isEqualTo("open"))
                .verifyComplete();
        verify(store, never()).save(any());
        ChangeRequest cr = opened();
        assertThat(cr.version()).isEqualTo("1.1.1");
        assertThat(cr.title()).isEqualTo("Provider Fleet API 1.1.1: SLO and alerts");
        ProviderDefinition next = ProviderYaml.parse(cr.yaml());
        assertThat(next.slo().successRate()).isEqualTo(99.9);
        assertThat(next.slo().p95Ms()).isEqualTo(500);
        assertThat(next.spec().alerts().channel()).isEqualTo("#fleet-alerts");
        assertThat(next.actions()).hasSize(2);
        assertThat(cr.checks()).extracting(Check::id).endsWith("slo-suggested");
        assertThat(event("provider.change-requested").orElseThrow().data().path("change").asText()).isEqualTo("slo");
        StepVerifier.create(as(handler.slo("fleet", new SetSlo(120.0, null, null)), OWNER)).expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(as(handler.slo("fleet", new SetSlo(null, 0, null)), OWNER)).expectError(IllegalArgumentException.class).verify();
    }

    @Test
    void overridesAndSloEditsReachEveryCallAtOnceAsTheyChangeNoCall() {
        when(store.get("fleet")).thenReturn(Optional.of(stored(YAML)));
        StepVerifier.create(as(handler.slo("fleet", new SetSlo(99.9, null, null)), OWNER)).expectNextCount(1).verifyComplete();
        assertThat(opened().strategy()).isEqualTo("all");
        assertThat(event("provider.change-requested").orElseThrow().data().path("strategy").asText()).isEqualTo("all");
    }

    @Test
    void anEditWhileACanaryRollsOutBuildsOnTheCandidateAndRollsOutAsACanaryToo() {
        when(store.get("fleet")).thenReturn(Optional.of(stored(YAML_V1)));
        when(releases.providerCandidate("fleet")).thenReturn(Mono.just("1.1.0"));
        when(store.version("fleet", "1.1.0")).thenReturn(Optional.of(stored(YAML)));
        StepVerifier.create(as(handler.slo("fleet", new SetSlo(99.9, null, null)), OWNER)).expectNextCount(1).verifyComplete();
        ChangeRequest cr = opened();
        assertThat(cr.version()).as("the next patch of the candidate, not of the current version").isEqualTo("1.1.1");
        assertThat(cr.strategy()).isEqualTo("canary");
        assertThat(cr.diff()).contains("-metadata: { name: fleet, title: Fleet API, owner: team-compute, version: 1.1.0 }");
    }

    @Test
    void aVersionKeptForACanaryCannotBePublishedAgain() {
        proven();
        when(store.get("fleet")).thenReturn(Optional.of(stored(YAML_V1)));
        when(store.version("fleet", "1.1.0")).thenReturn(Optional.of(stored(YAML)));
        when(changeRequests.all()).thenReturn(List.of());
        StepVerifier.create(as(handler.publish(new Publish(YAML, null)), OWNER))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(ConflictException.class).hasMessageContaining("1.1.0 of provider fleet is already published"))
                .verify();
    }

    @Test
    void aPartialSloKeepsTheOtherValues() {
        when(store.get("fleet")).thenReturn(Optional.of(stored(YAML)));
        StepVerifier.create(as(handler.slo("fleet", new SetSlo(null, 650, null)), OWNER)).expectNextCount(1).verifyComplete();
        ProviderDefinition next = ProviderYaml.parse(opened().yaml());
        assertThat(next.slo().successRate()).isEqualTo(99.5);
        assertThat(next.slo().p95Ms()).isEqualTo(650);
        assertThat(next.spec().alerts().channel()).isEqualTo("#compute-alerts");
    }

    @Test
    void theSameSloChangesNothingAndAnOpenChangeRequestWaits() {
        when(store.get("fleet")).thenReturn(Optional.of(stored(YAML)));
        StepVerifier.create(as(handler.slo("fleet", new SetSlo(99.5, 800, "#compute-alerts")), OWNER))
                .assertNext(a -> assertThat(a.status()).isEqualTo("unchanged"))
                .verifyComplete();
        when(changeRequests.all()).thenReturn(List.of(openProviderChange("1.1.1")));
        StepVerifier.create(as(handler.slo("fleet", new SetSlo(99.0, null, null)), OWNER)).expectError(ConflictException.class).verify();
        verify(changeRequests, never()).save(any());
    }

    @Test
    void aPublishWaitsForAnotherOpenChangeOfTheProvider() {
        proven();
        when(store.get("fleet")).thenReturn(Optional.of(stored(YAML_V1)));
        when(changeRequests.all()).thenReturn(List.of(openProviderChange("1.0.1")));
        StepVerifier.create(as(handler.publish(new Publish(YAML, null)), OWNER))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(ConflictException.class).hasMessageContaining("cr_open"))
                .verify();
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
