package com.iaf.platform.providers.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.common.ports.TelemetryPort;
import com.iaf.platform.common.ports.TelemetryPort.CallSample;
import com.iaf.platform.providers.adapters.outbound.HttpProviderCall.Operation;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.providers.domain.ProviderYaml;
import com.iaf.platform.providers.ports.outbound.GitSourcePort;
import com.iaf.platform.runs.ports.outbound.ProviderCallPort.ProviderCall;
import com.iaf.platform.runs.ports.outbound.ProviderCallPort.ProviderCallException;
import com.iaf.platform.testing.LocalHttp;
import com.iaf.platform.workflows.domain.WorkflowYaml;
import com.sun.net.httpserver.HttpServer;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class HttpProviderCallTest {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    static final ObjectMapper JSON = new ObjectMapper();

    HttpServer server;
    List<String> hits = new CopyOnWriteArrayList<>();
    AtomicInteger specHits = new AtomicInteger();
    CredentialsPort credentials;
    TelemetryPort telemetry;
    HttpProviderCall call;
    ProviderDefinition fleet;

    static String spec() {
        try (InputStream in = HttpProviderCallTest.class.getResourceAsStream("/openapi/machines-api.yaml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    void start() throws IOException {
        server = LocalHttp.start(ex -> {
            String path = ex.getRequestURI().getPath();
            String query = ex.getRequestURI().getRawQuery();
            String method = ex.getRequestMethod();
            hits.add(method + " " + path + (query == null ? "" : "?" + query));
            switch (method + " " + path) {
                case "GET /api/vms/vm-1" -> {
                    if (!"Bearer secret-token".equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                        LocalHttp.respond(ex, 401, "{\"error\":\"who are you\"}");
                    } else {
                        LocalHttp.respond(ex, 200, "{\"id\":\"vm-1\",\"name\":\"web\",\"powerState\":\"poweredOn\",\"monthlyCost\":3.5}");
                    }
                }
                case "POST /api/vms" -> LocalHttp.respond(ex, 201, "{\"created\":true,\"echo\":" + LocalHttp.body(ex) + "}");
                case "GET /api/jobs" -> LocalHttp.respond(ex, 200, "{\"query\":\"" + (query == null ? "" : query) + "\"}");
                case "GET /api/missing" -> LocalHttp.respond(ex, 404, "{\"error\":\"nope\"}");
                case "GET /api/flaky" -> LocalHttp.respond(ex, 503, "busy");
                case "GET /api/text" -> LocalHttp.respond(ex, 200, "plain words");
                case "GET /spec" -> {
                    specHits.incrementAndGet();
                    LocalHttp.respond(ex, 200, spec());
                }
                case "GET /api/machines/m-1" -> LocalHttp.respond(ex, 200, "{\"id\":\"m-1\",\"name\":\"one\"}");
                default -> LocalHttp.respond(ex, 404, "{}");
            }
        });
        credentials = mock(CredentialsPort.class);
        when(credentials.resolve("vault:fleet/token")).thenReturn(Mono.just("secret-token"));
        telemetry = mock(TelemetryPort.class);
        call = new HttpProviderCall(WebClient.builder(), credentials, telemetry, Clock.fixed(NOW, ZoneOffset.UTC));
        fleet = ProviderYaml.parse("""
                apiVersion: iaf.platform/v1
                kind: Provider
                metadata: { name: fleet, title: Fleet, owner: team-compute, version: 1.0.0 }
                spec:
                  type: http
                  endpoints: { dev: %s/api/ }
                  auth: { type: bearer, credential: vault:fleet/token }
                  actions:
                    - id: vm.get
                      operation: { method: GET, path: "/vms/{id}" }
                      effect: read
                      retry: safe
                      output: { type: object, properties: { id: { type: string }, name: { type: string } } }
                      sample: { id: "{{ $input.id }}", name: sample, job: "{{ $job.id }}" }
                    - id: vm.create
                      operation: { method: POST, path: /vms }
                      effect: create
                      retry: unsafe
                    - id: jobs.list
                      operation: { method: GET, path: "/jobs?target={target}&state={state}" }
                      effect: read
                      retry: safe
                    - { id: vm.missing, operation: { method: GET, path: /missing }, effect: read, retry: safe }
                    - { id: vm.flaky, operation: { method: GET, path: /flaky }, effect: read, retry: safe }
                    - { id: vm.text, operation: { method: GET, path: /text }, effect: read, retry: safe }
                  errors:
                    - { match: { status: 404 }, class: not_found, retry: false }
                    - { match: { status: 503 }, class: unavailable, retry: true }
                """.formatted(LocalHttp.url(server)));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    static JsonNode json(String s) {
        try {
            return JSON.readTree(s);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    ProviderCall callOf(String action, String input, boolean dryRun) {
        return new ProviderCall("job_1", 3, fleet, fleet.action(action).orElseThrow(), "dev", json(input), dryRun);
    }

    @Test
    void fillsPathParametersSendsTheBearerTokenAndRecordsTheCall() {
        StepVerifier.create(call.call(callOf("vm.get", "{\"id\":\"vm-1\"}", false)))
                .assertNext(r -> assertThat(r.path("name").asText()).isEqualTo("web"))
                .verifyComplete();
        assertThat(hits).containsExactly("GET /api/vms/vm-1");
        ArgumentCaptor<CallSample> captor = ArgumentCaptor.forClass(CallSample.class);
        verify(telemetry).record(captor.capture());
        CallSample s = captor.getValue();
        assertThat(s.provider()).isEqualTo("fleet");
        assertThat(s.action()).isEqualTo("vm.get");
        assertThat(s.environment()).isEqualTo("dev");
        assertThat(s.ok()).isTrue();
        assertThat(s.jobId()).isEqualTo("job_1");
        assertThat(s.at()).isEqualTo(NOW);
        assertThat(s.durationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void refusesAnEndpointAtACloudMetadataAddress() {
        ProviderDefinition metadata = ProviderYaml.parse(ProviderYaml.toYaml(fleet).replace(LocalHttp.url(server) + "/api/", "http://169.254.169.254/latest/"));
        StepVerifier.create(call.call(new ProviderCall("job_1", 3, metadata, metadata.action("vm.get").orElseThrow(), "dev", json("{\"id\":\"x\"}"), false)))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(ProviderCallException.class).hasMessageContaining("link-local or cloud metadata address");
                    assertThat(((ProviderCallException) e).errorClass()).isEqualTo("misconfigured");
                })
                .verify();
        assertThat(hits).isEmpty();
    }

    @Test
    void sendsTheRemainingInputAsTheJsonBody() {
        StepVerifier.create(call.call(callOf("vm.create", "{\"name\":\"web\",\"cpu\":2}", false)))
                .assertNext(r -> {
                    assertThat(r.path("created").asBoolean()).isTrue();
                    assertThat(r.path("echo").path("name").asText()).isEqualTo("web");
                    assertThat(r.path("echo").path("cpu").asInt()).isEqualTo(2);
                })
                .verifyComplete();
    }

    @Test
    void fillsQueryTemplatesAndDropsTheMissingOnes() {
        StepVerifier.create(call.call(callOf("jobs.list", "{\"target\":\"vm 1\",\"extra\":true}", false)))
                .assertNext(r -> assertThat(r.path("query").asText()).isEqualTo("target=vm%201&extra=true"))
                .verifyComplete();
    }

    @Test
    void classifiesErrorsByTheProvidersTableAndRecordsThem() {
        StepVerifier.create(call.call(callOf("vm.missing", "{}", false)))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(ProviderCallException.class);
                    ProviderCallException p = (ProviderCallException) e;
                    assertThat(p.errorClass()).isEqualTo("not_found");
                    assertThat(p.retryable()).isFalse();
                    assertThat(p.status()).isEqualTo(404);
                    assertThat(p.getMessage()).contains("nope");
                })
                .verify();
        StepVerifier.create(call.call(callOf("vm.flaky", "{}", false)))
                .expectErrorSatisfies(e -> {
                    ProviderCallException p = (ProviderCallException) e;
                    assertThat(p.errorClass()).isEqualTo("unavailable");
                    assertThat(p.retryable()).isTrue();
                })
                .verify();
        ArgumentCaptor<CallSample> captor = ArgumentCaptor.forClass(CallSample.class);
        verify(telemetry, org.mockito.Mockito.times(2)).record(captor.capture());
        assertThat(captor.getAllValues()).extracting(CallSample::errorClass).containsExactly("not_found", "unavailable");
        assertThat(captor.getAllValues()).extracting(CallSample::ok).containsOnly(false);
    }

    @Test
    void aRejectedCredentialIsUnauthorizedByDefault() {
        when(credentials.resolve("vault:fleet/token")).thenReturn(Mono.just("wrong"));
        StepVerifier.create(call.call(callOf("vm.get", "{\"id\":\"vm-1\"}", false)))
                .expectErrorSatisfies(e -> assertThat(((ProviderCallException) e).errorClass()).isEqualTo("unauthorized"))
                .verify();
    }

    @Test
    void aMissingCredentialNeverReachesTheSystem() {
        when(credentials.resolve("vault:fleet/token")).thenReturn(Mono.empty());
        StepVerifier.create(call.call(callOf("vm.get", "{\"id\":\"vm-1\"}", false)))
                .expectErrorSatisfies(e -> assertThat(((ProviderCallException) e).errorClass()).isEqualTo("credential_missing"))
                .verify();
        assertThat(hits).isEmpty();
        verify(telemetry, never()).record(any());
    }

    @Test
    void nonJsonAnswersAreWrapped() {
        StepVerifier.create(call.call(callOf("vm.text", "{}", false)))
                .assertNext(r -> assertThat(r.path("body").asText()).isEqualTo("plain words"))
                .verifyComplete();
    }

    @Test
    void aDryRunAnswersFromTheSampleWithoutTouchingTheSystem() {
        StepVerifier.create(call.call(callOf("vm.get", "{\"id\":\"vm-7\"}", true)))
                .assertNext(r -> {
                    assertThat(r.path("id").asText()).isEqualTo("vm-7");
                    assertThat(r.path("name").asText()).isEqualTo("sample");
                    assertThat(r.path("job").asText()).isEqualTo("job_1");
                })
                .verifyComplete();
        assertThat(hits).isEmpty();
        verify(telemetry, never()).record(any());
    }

    @Test
    void looksOperationsUpInThePinnedSpecAndCachesIt() {
        String spec = spec();
        ProviderDefinition machines = ProviderYaml.parse("""
                apiVersion: iaf.platform/v1
                kind: Provider
                metadata: { name: machines, title: Machines, owner: team-compute, version: 1.0.0 }
                spec:
                  type: openapi
                  spec: { url: %s/spec, sha256: %s }
                  endpoints: { dev: %s/api }
                  auth: { type: none }
                  actions:
                    - { id: machine.get, operation: getMachine, effect: read, retry: safe }
                    - { id: machine.nope, operation: nothingHere, effect: read, retry: safe }
                """.formatted(LocalHttp.url(server), WorkflowYaml.sha256(spec), LocalHttp.url(server)));
        ProviderCall get = new ProviderCall(null, 1, machines, machines.action("machine.get").orElseThrow(), "dev", json("{\"machineId\":\"m-1\"}"), false);
        StepVerifier.create(call.call(get)).assertNext(r -> assertThat(r.path("name").asText()).isEqualTo("one")).verifyComplete();
        StepVerifier.create(call.call(get)).expectNextCount(1).verifyComplete();
        assertThat(specHits.get()).isEqualTo(1);
        assertThat(hits).contains("GET /api/machines/m-1");
        ProviderCall nope = new ProviderCall(null, 1, machines, machines.action("machine.nope").orElseThrow(), "dev", json("{}"), false);
        StepVerifier.create(call.call(nope))
                .expectErrorSatisfies(e -> assertThat(((ProviderCallException) e).errorClass()).isEqualTo("misconfigured"))
                .verify();
    }

    @Test
    void aSpecFromGitIsReadAtThePinnedCommitNotTheMovingRef() {
        String spec = spec();
        String commit = "3f2a9c1d0e4b5a6c7d8e9f0a1b2c3d4e5f6a7b8c";
        GitSourcePort git = mock(GitSourcePort.class);
        when(git.file("https://github.com/acme/machines-api", commit, "openapi.yaml", "vault:github/read-token"))
                .thenReturn(Mono.just(new GitSourcePort.GitFile("openapi.yaml", spec, commit)));
        String yaml = """
                apiVersion: iaf.platform/v1
                kind: Provider
                metadata: { name: machines, title: Machines, owner: team-compute, version: 1.0.0 }
                spec:
                  type: openapi
                  spec:
                    git: { repo: https://github.com/acme/machines-api, ref: main, path: openapi.yaml, credential: vault:github/read-token }
                    commit: %s
                    sha256: %s
                  endpoints: { dev: %s/api }
                  auth: { type: none }
                  actions:
                    - { id: machine.get, operation: getMachine, effect: read, retry: safe }
                """.formatted(commit, WorkflowYaml.sha256(spec), LocalHttp.url(server));
        ProviderDefinition machines = ProviderYaml.parse(yaml);
        ProviderCall get = new ProviderCall(null, 1, machines, machines.action("machine.get").orElseThrow(), "dev", json("{\"machineId\":\"m-1\"}"), false);
        HttpProviderCall withGit = new HttpProviderCall(WebClient.builder(), credentials, telemetry, Clock.fixed(NOW, ZoneOffset.UTC), git);

        StepVerifier.create(withGit.call(get)).assertNext(r -> assertThat(r.path("name").asText()).isEqualTo("one")).verifyComplete();
        StepVerifier.create(withGit.call(get)).expectNextCount(1).verifyComplete();
        verify(git, org.mockito.Mockito.times(1)).file(any(), any(), any(), any());

        // a platform without a Git reader says so instead of guessing
        ProviderDefinition other = ProviderYaml.parse(yaml.replace(WorkflowYaml.sha256(spec), "0".repeat(64)));
        StepVerifier.create(call.call(new ProviderCall(null, 1, other, other.action("machine.get").orElseThrow(), "dev", json("{\"machineId\":\"m-1\"}"), false)))
                .expectErrorSatisfies(e -> assertThat(((ProviderCallException) e).errorClass()).isEqualTo("spec_unavailable"))
                .verify();
    }

    @Test
    void unknownEnvironmentsAndTypesAreMisconfigured() {
        ProviderCall staging = new ProviderCall(null, 1, fleet, fleet.action("vm.get").orElseThrow(), "staging", json("{\"id\":\"vm-1\"}"), false);
        StepVerifier.create(call.call(staging))
                .expectErrorSatisfies(e -> assertThat(((ProviderCallException) e).errorClass()).isEqualTo("misconfigured"))
                .verify();
        ProviderDefinition ansible = ProviderYaml.parse("""
                apiVersion: iaf.platform/v1
                kind: Provider
                metadata: { name: pb, owner: t, version: 1.0.0 }
                spec:
                  type: ansible
                  actions:
                    - { id: vm.restart, playbook: vm/restart.yml, effect: change }
                """);
        StepVerifier.create(call.call(new ProviderCall(null, 1, ansible, ansible.action("vm.restart").orElseThrow(), "dev", json("{}"), false)))
                .expectErrorSatisfies(e -> assertThat(((ProviderCallException) e).errorClass()).isEqualTo("unsupported"))
                .verify();
    }

    @Test
    void aCustomCodeProviderFailsClearlyOutsideTemporalButStillPreviews() {
        ProviderDefinition sdk = ProviderYaml.parse("""
                apiVersion: iaf.platform/v1
                kind: Provider
                metadata: { name: ip-pool, owner: team-platform, version: 1.0.0 }
                spec:
                  type: sdk
                  actions:
                    - { id: address.reserve, effect: create, sample: { address: 10.0.0.7 } }
                """);
        StepVerifier.create(call.call(new ProviderCall("job_1", 1, sdk, sdk.action("address.reserve").orElseThrow(), "dev", json("{}"), false)))
                .expectErrorSatisfies(e -> {
                    ProviderCallException failure = (ProviderCallException) e;
                    assertThat(failure.errorClass()).isEqualTo("unsupported");
                    assertThat(failure.retryable()).isFalse();
                    assertThat(failure.getMessage()).contains("custom-code provider", "Temporal worker", "provider-ip-pool", "temporal profile");
                })
                .verify();
        assertThat(hits).isEmpty();
        StepVerifier.create(call.call(new ProviderCall("job_1", 1, sdk, sdk.action("address.reserve").orElseThrow(), "dev", json("{}"), true)))
                .assertNext(out -> assertThat(out.path("address").asText()).isEqualTo("10.0.0.7"))
                .verifyComplete();
    }

    @Test
    void urlsAreBuiltFromTheTemplateTheQueryParametersAndTheRest() {
        ObjectNode input = (ObjectNode) json("{\"target\":\"a b\",\"state\":\"running\",\"page\":2,\"nested\":{\"x\":1}}");
        String url = HttpProviderCall.url("http://h/api", new Operation("GET", "/jobs?target={target}&state={state}", Set.of()), input);
        assertThat(url).isEqualTo("http://h/api/jobs?target=a%20b&state=running&page=2&nested=%7B%22x%22%3A1%7D");
        assertThat(input.isEmpty()).isTrue();
        ObjectNode body = (ObjectNode) json("{\"path\":\"databases/orders\",\"data\":{\"a\":1},\"limit\":5}");
        String post = HttpProviderCall.url("http://h", new Operation("POST", "/secret/data/{path}", Set.of("limit")), body);
        assertThat(post).isEqualTo("http://h/secret/data/databases%2Forders?limit=5");
        assertThat(body.fieldNames()).toIterable().containsExactly("data");
        assertThat(HttpProviderCall.queryNames("/jobs?target={target}&state={state}")).containsExactly("target", "state");
        assertThat(HttpProviderCall.queryNames("/jobs")).isEmpty();
    }

    @Test
    void aPathParameterFillsOneSegmentAndNeverWalksOutOfThePath() {
        Operation get = new Operation("GET", "/vms/{id}", Set.of());
        assertThat(HttpProviderCall.url("http://h", get, (ObjectNode) json("{\"id\":\"a/b\"}"))).isEqualTo("http://h/vms/a%2Fb");
        assertThat(HttpProviderCall.url("http://h", get, (ObjectNode) json("{\"id\":\"web..example\"}"))).isEqualTo("http://h/vms/web..example");
        for (String escape : List.of("..", ".", "../admin", "a/../../other", "a/./b", "..\\admin", "x/..")) {
            ObjectNode input = JSON.createObjectNode().put("id", escape);
            assertThatThrownBy(() -> HttpProviderCall.url("http://h", get, input))
                    .as(escape)
                    .isInstanceOfSatisfying(ProviderCallException.class, e -> {
                        assertThat(e.errorClass()).isEqualTo("invalid_input");
                        assertThat(e.retryable()).isFalse();
                        assertThat(e.getMessage()).contains("id");
                    });
        }
        // a query value is not a path: it is encoded whole and may hold dots
        ObjectNode query = (ObjectNode) json("{\"target\":\"../etc\",\"state\":\"a/b\"}");
        assertThat(HttpProviderCall.url("http://h", new Operation("GET", "/jobs?target={target}&state={state}", Set.of()), query))
                .isEqualTo("http://h/jobs?target=..%2Fetc&state=a%2Fb");
    }

    @Test
    void anInputThatWouldLeaveThePathIsRefusedBeforeTheCredentialIsSent() {
        StepVerifier.create(call.call(callOf("vm.get", "{\"id\":\"../../admin\"}", false)))
                .expectErrorSatisfies(e -> assertThat(((ProviderCallException) e).errorClass()).isEqualTo("invalid_input"))
                .verify();
        StepVerifier.create(call.call(callOf("vm.get", "{\"id\":\"vm-1/../../admin\"}", false)))
                .expectError(ProviderCallException.class)
                .verify();
        assertThat(hits).isEmpty();
        verify(telemetry, never()).record(any());
    }
}
