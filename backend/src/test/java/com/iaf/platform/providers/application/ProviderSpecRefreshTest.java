package com.iaf.platform.providers.application;

import static com.iaf.platform.providers.application.ProviderCommandHandlerTest.CLOCK;
import static com.iaf.platform.providers.application.ProviderCommandHandlerTest.NOW;
import static com.iaf.platform.providers.application.ProviderCommandHandlerTest.OUTSIDER;
import static com.iaf.platform.providers.application.ProviderCommandHandlerTest.OWNER;
import static com.iaf.platform.providers.application.ProviderCommandHandlerTest.as;
import static com.iaf.platform.providers.application.ProviderCommandHandlerTest.json;
import static com.iaf.platform.providers.application.ProviderCommandHandlerTest.spec;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.platform.assistant.ports.inbound.AssistantCommands;
import com.iaf.platform.common.domain.ConflictException;
import com.iaf.platform.common.domain.ForbiddenException;
import com.iaf.platform.common.domain.Problem;
import com.iaf.platform.common.events.DomainEvent;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.common.ports.CredentialsPort.CredentialInfo;
import com.iaf.platform.providers.adapters.outbound.InMemoryReviewEvidence;
import com.iaf.platform.providers.domain.OpenApiImporter;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.providers.domain.ProviderYaml;
import com.iaf.platform.providers.domain.ReviewEvidence.ActionTry;
import com.iaf.platform.providers.domain.SpecImport;
import com.iaf.platform.providers.ports.inbound.ProviderCommands.RefreshSpec;
import com.iaf.platform.providers.ports.outbound.GitSourcePort;
import com.iaf.platform.providers.ports.outbound.GitSourcePort.GitFile;
import com.iaf.platform.providers.ports.outbound.HttpProbePort;
import com.iaf.platform.providers.ports.outbound.ProviderStorePort;
import com.iaf.platform.providers.ports.outbound.ProviderStorePort.StoredProvider;
import com.iaf.platform.releases.adapters.outbound.InMemoryReleaseStore;
import com.iaf.platform.releases.application.ReleaseQueryHandler;
import com.iaf.platform.runs.ports.outbound.ProviderCallPort;
import com.iaf.platform.workflows.domain.ChangeRequest;
import com.iaf.platform.workflows.domain.ChangeRequest.Check;
import com.iaf.platform.workflows.domain.WorkflowYaml;
import com.iaf.platform.workflows.ports.outbound.ChangeRequestPort;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Refresh spec: the provider's OpenAPI document read again from where its file says, and the change it makes as a change request. */
class ProviderSpecRefreshTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String REPO = "https://github.com/acme/machines-api";
    static final String TOKEN = "vault:github/read-token";
    static final String COMMIT_1 = "1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a";
    static final String COMMIT_2 = "2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b";
    static final String SPEC_V1 = spec();
    /** The vendor's next spec: machines now say how much memory they have. */
    static final String SPEC_V2 = SPEC_V1.replace("version: 2.4.0", "version: 2.5.0")
            .replace("        powerState: { type: string, enum: [on, off] }\n", "        powerState: { type: string, enum: [on, off] }\n        memoryGb: { type: integer }\n");
    /** A spec that renamed the delete operation. */
    static final String SPEC_RENAMED = SPEC_V2.replace("operationId: deleteMachine", "operationId: removeMachine");

    ProviderStorePort store;
    ChangeRequestPort changeRequests;
    HttpProbePort http;
    GitSourcePort git;
    InMemoryReviewEvidence evidence;
    List<DomainEvent> published;
    CredentialsPort credentials;
    ProviderCommandHandler handler;

    @BeforeEach
    void setUp() {
        store = mock(ProviderStorePort.class);
        changeRequests = mock(ChangeRequestPort.class);
        http = mock(HttpProbePort.class);
        git = mock(GitSourcePort.class);
        evidence = new InMemoryReviewEvidence();
        EventBus events = new EventBus(CLOCK);
        published = new ArrayList<>();
        events.register(published::add);
        credentials = mock(CredentialsPort.class);
        when(credentials.info(any())).thenReturn(Mono.empty());
        handler = new ProviderCommandHandler(store, changeRequests, mock(ProviderCallPort.class), mock(AssistantCommands.class), http, events, CLOCK,
                evidence, git, new ReleaseQueryHandler(new InMemoryReleaseStore()), credentials);
    }

    static String toJson(JsonNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The machines provider as the wizard wrote it from {@code spec}, read from Git at {@code commit}. */
    static String machines(String spec, String commit, String overrides) {
        SpecImport s = OpenApiImporter.parse(spec);
        SpecImport.Operation get = s.operation("getMachine").orElseThrow();
        SpecImport.Operation list = s.operation("listMachines").orElseThrow();
        SpecImport.Operation delete = s.operation("deleteMachine").orElseThrow();
        return String.join("\n",
                "apiVersion: iaf.platform/v1",
                "kind: Provider",
                "metadata: { name: machines, title: Machines API, owner: team-compute, version: 2.0.0 }",
                "spec:",
                "  type: openapi",
                "  spec:",
                "    git: { repo: \"" + REPO + "\", ref: main, path: openapi.yaml, credential: \"" + TOKEN + "\" }",
                "    commit: \"" + commit + "\"",
                "    sha256: " + s.sha256(),
                "  endpoints: { dev: \"https://machines-dev.example.com\" }",
                "  auth: { type: bearer, credential: \"vault:machines/token\" }",
                "  actions:",
                "    - { id: machine.get, title: Get machine, operation: getMachine, effect: read, retry: safe, input: " + toJson(get.input())
                        + ", output: " + toJson(get.output()) + " }",
                "    - { id: machine.list, title: List machines, operation: listMachines, effect: read, retry: safe, input: " + toJson(list.input())
                        + ", output: " + toJson(list.output()) + " }",
                "    - { id: machine.delete, title: Delete machine, operation: deleteMachine, effect: delete, retry: unsafe, approval: required, "
                        + "plan: machine.get, input: " + toJson(delete.input()) + " }",
                "  errors:",
                "    - { match: { status: 404 }, class: not_found, retry: false }",
                "  slo: { successRate: 99.5, p95Ms: 800 }",
                "  alerts: { channel: \"#compute-alerts\" }",
                overrides);
    }

    static final String OVERRIDES = String.join("\n",
            "  overrides:",
            "    machine.get:",
            "      response: { type: object, properties: { id: { type: string }, name: { type: string }, powerState: { type: string }, memoryGb: { type: integer } } }",
            "    machine.list:",
            "      response: { type: object, properties: { items: { type: array }, total: { type: integer }, next: { type: string } } }",
            "");

    void stored(String yaml) {
        when(store.get("machines")).thenReturn(Optional.of(new StoredProvider(ProviderYaml.parse(yaml), yaml, "providers/team-compute/machines.yaml",
                NOW.minusSeconds(86400), "mateo")));
    }

    void gitAnswers(String ref, String spec, String commit) {
        when(git.file(REPO, ref, "openapi.yaml", TOKEN)).thenReturn(Mono.just(new GitFile("openapi.yaml", spec, commit)));
    }

    ChangeRequest opened() {
        ArgumentCaptor<ChangeRequest> cr = ArgumentCaptor.forClass(ChangeRequest.class);
        verify(changeRequests).save(cr.capture());
        return cr.getValue();
    }

    static Check check(ChangeRequest cr, String id) {
        return cr.checks().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void aNewerSpecOpensAChangeRequestWithTheNewSchemasThePinAndTheNextMinorVersion() {
        stored(machines(SPEC_V1, COMMIT_1, ""));
        gitAnswers("main", SPEC_V2, COMMIT_2);

        StepVerifier.create(as(handler.refreshSpec("machines", new RefreshSpec(null, null)), OWNER))
                .assertNext(a -> {
                    assertThat(a.status()).isEqualTo("open");
                    assertThat(a.links()).containsEntry("changeRequest", "/api/v1/change-requests/" + a.id());
                })
                .verifyComplete();

        ChangeRequest cr = opened();
        assertThat(cr.kind()).isEqualTo(ChangeRequest.Kind.PROVIDER);
        assertThat(cr.version()).isEqualTo("2.1.0");
        assertThat(cr.author()).isEqualTo("mateo");
        assertThat(cr.title()).isEqualTo("Provider Machines API 2.1.0: spec refreshed");
        assertThat(cr.strategy()).as("a new spec can change the calls, so it rolls out through a canary").isEqualTo("canary");
        ProviderDefinition next = ProviderYaml.parse(cr.yaml());
        assertThat(next.spec().specSource().commit()).isEqualTo(COMMIT_2);
        assertThat(next.spec().specSource().sha256()).isEqualTo(WorkflowYaml.sha256(SPEC_V2));
        assertThat(next.spec().specSource().git().ref()).isEqualTo("main");
        assertThat(next.action("machine.get").orElseThrow().output().path("properties").has("memoryGb")).isTrue();
        assertThat(cr.diff()).contains("+++ b/providers/team-compute/machines.yaml").contains(COMMIT_2).contains("memoryGb");
        assertThat(cr.checksPassed()).isTrue();
        assertThat(check(cr, ProviderCommandHandler.SPEC_REFRESH).detail())
                .isEqualTo("Read openapi.yaml in " + REPO + " at 2b2b2b2b2b2b (spec version 2.5.0): 2 of 3 actions' schemas changed");
        assertThat(published).anySatisfy(e -> {
            assertThat(e.type()).isEqualTo(ProviderCommandHandler.CHANGE_REQUESTED_EVENT);
            assertThat(e.data().path("change").asText()).isEqualTo("spec-refresh");
        });
    }

    @Test
    void theSameDocumentChangesNothing() {
        stored(machines(SPEC_V1, COMMIT_1, ""));
        gitAnswers("main", SPEC_V1, COMMIT_2);

        StepVerifier.create(as(handler.refreshSpec("machines", null), OWNER))
                .assertNext(a -> {
                    assertThat(a.id()).isEqualTo("machines");
                    assertThat(a.status()).isEqualTo("unchanged");
                })
                .verifyComplete();
        verify(changeRequests, never()).save(any());
    }

    @Test
    void anOverrideStaysUntilTheNewSpecAgreesWithTheRealResponse() {
        stored(machines(SPEC_V1, COMMIT_1, OVERRIDES));
        gitAnswers("main", SPEC_V2, COMMIT_2);
        evidence.recordTry("machines", new ActionTry("machine.get", "dev", true, 200, 90, null,
                json("{\"id\":\"m-1\",\"name\":\"web\",\"powerState\":\"on\",\"memoryGb\":16}"), null, NOW, "mateo"));
        evidence.recordTry("machines", new ActionTry("machine.list", "dev", true, 200, 90, null,
                json("{\"items\":[],\"total\":0,\"next\":\"page-2\"}"), null, NOW, "mateo"));

        StepVerifier.create(as(handler.refreshSpec("machines", new RefreshSpec(null, null)), OWNER)).expectNextCount(1).verifyComplete();

        ChangeRequest cr = opened();
        ProviderDefinition next = ProviderYaml.parse(cr.yaml());
        assertThat(next.override("machine.get")).isNull();
        assertThat(next.override("machine.list").path("response").path("properties").has("next")).isTrue();
        assertThat(check(cr, ProviderCommandHandler.SPEC_REFRESH).detail())
                .endsWith("; override dropped for machine.get (the spec now agrees with the real response); override kept for machine.list");
        assertThat(check(cr, "responses").status()).isEqualTo("passed");
    }

    @Test
    void aSampleTheWizardRecordedCountsAsARealResponse() {
        String yaml = machines(SPEC_V1, COMMIT_1, OVERRIDES).replace("operation: getMachine,",
                "operation: getMachine, sample: { id: m-1, name: web, powerState: \"on\", memoryGb: 16 }, recorded: { environment: dev, at: \"2026-10-16T09:00:00Z\" },");
        stored(yaml);
        gitAnswers("main", SPEC_V2, COMMIT_2);

        StepVerifier.create(as(handler.refreshSpec("machines", null), OWNER)).expectNextCount(1).verifyComplete();

        ProviderDefinition next = ProviderYaml.parse(opened().yaml());
        assertThat(next.override("machine.get")).isNull();
        assertThat(next.override("machine.list")).isNotNull();
    }

    @Test
    void aRealResponseTheNewSchemaStillContradictsFailsTheResponsesCheck() {
        stored(machines(SPEC_V1, COMMIT_1, ""));
        gitAnswers("main", SPEC_V2, COMMIT_2);
        evidence.recordTry("machines", new ActionTry("machine.get", "dev", true, 200, 90, null,
                json("{\"id\":\"m-1\",\"name\":\"web\",\"uptime\":\"5d\"}"), null, NOW, "mateo"));

        StepVerifier.create(as(handler.refreshSpec("machines", null), OWNER)).expectNextCount(1).verifyComplete();

        ChangeRequest cr = opened();
        assertThat(check(cr, "responses").status()).isEqualTo("failed");
        assertThat(check(cr, "responses").detail()).contains("machine.get's real response still disagrees");
        assertThat(cr.checksPassed()).isFalse();
    }

    @Test
    void anotherRefIsReadAndWrittenToTheFile() {
        stored(machines(SPEC_V1, COMMIT_1, ""));
        gitAnswers("v2.5.0", SPEC_V2, COMMIT_2);

        StepVerifier.create(as(handler.refreshSpec("machines", new RefreshSpec(" v2.5.0 ", null)), OWNER)).expectNextCount(1).verifyComplete();

        assertThat(ProviderYaml.parse(opened().yaml()).spec().specSource().git().ref()).isEqualTo("v2.5.0");
    }

    @Test
    void aSpecThatDropsAnOperationAnActionCallsIsAConflict() {
        stored(machines(SPEC_V1, COMMIT_1, ""));
        gitAnswers("main", SPEC_RENAMED, COMMIT_2);

        StepVerifier.create(as(handler.refreshSpec("machines", null), OWNER))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(ConflictException.class).hasMessage("The new spec drops 1 operation that provider machines's actions call");
                    @SuppressWarnings("unchecked")
                    List<Problem> problems = (List<Problem>) ((ConflictException) e).detail();
                    assertThat(problems).singleElement().satisfies(p -> {
                        assertThat(p.code()).isEqualTo("refresh.operationGone");
                        assertThat(p.step()).isEqualTo("machine.delete");
                        assertThat(p.field()).isEqualTo("spec.actions[2].operation");
                    });
                })
                .verify();
        verify(changeRequests, never()).save(any());
    }

    @Test
    void aRefreshWaitsForTheProvidersOpenChangeRequest() {
        stored(machines(SPEC_V1, COMMIT_1, ""));
        when(changeRequests.all()).thenReturn(List.of(new ChangeRequest("cr-7", ChangeRequest.Kind.PROVIDER, "machines", "2.0.1", "change/provider-machines-2.0.1",
                "Provider Machines API 2.0.1", "ana", ChangeRequest.Status.OPEN, List.of(), "all", "", "", NOW, null, null, null)));

        StepVerifier.create(as(handler.refreshSpec("machines", null), OWNER))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(ConflictException.class)
                        .hasMessage("Change request cr-7 for provider machines 2.0.1 is still open; merge or close it first"))
                .verify();
        verify(git, never()).file(any(), any(), any(), any());
    }

    @Test
    void theReadTokenGoesToTheRepositoryOnlyWhenTheCallerMayUseIt() {
        stored(machines(SPEC_V1, COMMIT_1, ""));
        gitAnswers("main", SPEC_V2, COMMIT_2);
        when(credentials.info(TOKEN)).thenReturn(Mono.just(new CredentialInfo("github/read-token", "token", TOKEN, "team-data", NOW)));
        StepVerifier.create(as(handler.refreshSpec("machines", null), OWNER))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(ForbiddenException.class).hasMessageContaining(TOKEN).hasMessageContaining("team-data"))
                .verify();
        verify(git, never()).file(any(), any(), any(), any());
        verify(changeRequests, never()).save(any());
        when(credentials.info(TOKEN)).thenReturn(Mono.just(new CredentialInfo("github/read-token", "token", TOKEN, "team-compute", NOW)));
        StepVerifier.create(as(handler.refreshSpec("machines", null), OWNER)).assertNext(a -> assertThat(a.status()).isEqualTo("open")).verifyComplete();
    }

    @Test
    void onlyTheOwnerTeamRefreshesAndOnlyAnExistingProvider() {
        stored(machines(SPEC_V1, COMMIT_1, ""));
        StepVerifier.create(as(handler.refreshSpec("machines", null), OUTSIDER)).expectError(ForbiddenException.class).verify();
        when(store.get("nothing")).thenReturn(Optional.empty());
        StepVerifier.create(as(handler.refreshSpec("nothing", null), OWNER))
                .expectError(com.iaf.platform.common.domain.NotFoundException.class).verify();
    }

    @Test
    void aUrlSpecIsFetchedAgainAndAPastedOneTakesTheNewDocument() {
        String byUrl = machines(SPEC_V1, COMMIT_1, "").replace(
                "    git: { repo: \"" + REPO + "\", ref: main, path: openapi.yaml, credential: \"" + TOKEN + "\" }\n    commit: \"" + COMMIT_1 + "\"",
                "    url: \"https://machines.example.com/openapi.yaml\"");
        stored(byUrl);
        when(http.fetch("https://machines.example.com/openapi.yaml")).thenReturn(Mono.just(SPEC_V2));
        StepVerifier.create(as(handler.refreshSpec("machines", null), OWNER)).assertNext(a -> assertThat(a.status()).isEqualTo("open")).verifyComplete();
        StepVerifier.create(as(handler.refreshSpec("machines", new RefreshSpec("v2", null)), OWNER))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ref only applies to a spec read from Git"))
                .verify();

        String pasted = byUrl.replace("    url: \"https://machines.example.com/openapi.yaml\"", "    content: \"" + SPEC_V1.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"");
        stored(pasted);
        StepVerifier.create(as(handler.refreshSpec("machines", null), OWNER))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("paste the new document as content"))
                .verify();
        StepVerifier.create(as(handler.refreshSpec("machines", new RefreshSpec(null, SPEC_V2)), OWNER))
                .assertNext(a -> assertThat(a.status()).isEqualTo("open"))
                .verifyComplete();
        ArgumentCaptor<ChangeRequest> crs = ArgumentCaptor.forClass(ChangeRequest.class);
        verify(changeRequests, org.mockito.Mockito.times(2)).save(crs.capture());
        assertThat(ProviderYaml.parse(crs.getAllValues().get(1).yaml()).spec().specSource().content()).isEqualTo(SPEC_V2);
    }

    @Test
    void onlyOpenapiProvidersHaveASpecToRefresh() {
        String http = machines(SPEC_V1, COMMIT_1, "").replace("type: openapi", "type: http");
        stored(http);
        StepVerifier.create(as(handler.refreshSpec("machines", null), OWNER))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalArgumentException.class).hasMessage("Provider machines has no OpenAPI spec to refresh"))
                .verify();
    }
}
