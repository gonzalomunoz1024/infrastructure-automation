package com.iaf.platform.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.assistant.adapters.outbound.InMemorySessionStore;
import com.iaf.platform.assistant.domain.AssistantEvent;
import com.iaf.platform.assistant.domain.AssistantSession;
import com.iaf.platform.assistant.domain.Events;
import com.iaf.platform.assistant.domain.ProviderDraft;
import com.iaf.platform.assistant.domain.WorkflowDraft;
import com.iaf.platform.assistant.ports.outbound.AssistantPort;
import com.iaf.platform.assistant.ports.outbound.AssistantPort.AssistantRequest;
import com.iaf.platform.assistant.ports.outbound.AssistantPort.PlaybookSync;
import com.iaf.platform.assistant.ports.outbound.AssistantPort.ProviderRequest;
import com.iaf.platform.common.domain.ForbiddenException;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.common.domain.Problem;
import com.iaf.platform.common.events.DomainEvent;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.common.schema.Schemas;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.identity.application.CurrentIdentity;
import com.iaf.platform.runs.domain.DryRunResult;
import com.iaf.platform.runs.ports.inbound.RunsCommands;
import com.iaf.platform.workflows.domain.WorkflowRecord;
import com.iaf.platform.workflows.domain.WorkflowStatus;
import com.iaf.platform.workflows.domain.WorkflowYaml;
import com.iaf.platform.workflows.ports.inbound.WorkflowQueries;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Session lifecycle and the event stream, with a scripted agent behind the port. */
class AssistantCommandHandlerTest {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final Identity WHO = new Identity("u-ada", "Ada", "ada@example.com", List.of("team-compute", "team-platform"), List.of("ops"), false, Identity.Kind.USER);
    static final Identity OTHER = new Identity("u-bob", "Bob", null, List.of("team-data"), List.of("ops"), false, Identity.Kind.USER);
    static final Identity ADMIN = new Identity("u-root", "Root", null, List.of(), List.of("admin"), true, Identity.Kind.USER);
    static final Identity ROBOT = new Identity("sa_agent", "Agent", null, List.of("team-compute"), List.of(), true, Identity.Kind.SERVICE);

    static final String YAML = """
            apiVersion: iaf.platform/v1
            kind: Workflow
            metadata:
              name: restart-vm
              title: Restart VM
              noun: vm
              verb: restart
              owner: team-compute
              folder: Compute
              version: 0.1.0
            api:
              method: POST
              path: /api/v1/vms/{vmId}:restart
              input:
                type: object
                required: [vmId]
                properties:
                  vmId: { type: string, title: VM id }
            workflow:
              do:
                - restart:
                    name: Restart VM
                    call: provider
                    with:
                      provider: vsphere
                      action: vm.restart
                      input: { id: "{{ $input.vmId }}" }
            """;

    static final String BAD_YAML = YAML.replace("vm.restart", "vm.reboot");

    /** An agent that answers with a scripted stream and remembers what it was asked; it checks its own drafts unless told otherwise. */
    static final class ScriptedAgent implements AssistantPort {
        final AtomicReference<AssistantRequest> last = new AtomicReference<>();
        final List<AssistantRequest> asked = new CopyOnWriteArrayList<>();
        final Function<AssistantRequest, Flux<AssistantEvent>> script;
        boolean platformChecks;
        AtomicReference<ProviderRequest> providerAsked = new AtomicReference<>();
        List<PlaybookSync> synced = List.of();

        ScriptedAgent(Function<AssistantRequest, Flux<AssistantEvent>> script) {
            this.script = script;
        }

        static ScriptedAgent of(AssistantEvent... events) {
            return new ScriptedAgent(r -> Flux.just(events));
        }

        /** Like Devin: the platform checks what it writes. */
        ScriptedAgent checkedByThePlatform() {
            platformChecks = true;
            return this;
        }

        @Override
        public Flux<AssistantEvent> run(AssistantRequest request) {
            last.set(request);
            asked.add(request);
            return script.apply(request);
        }

        @Override
        public Mono<ProviderDraft> draftProvider(String description, Identity who) {
            return Mono.just(new ProviderDraft("kind: Provider\n", "for " + who.id() + ": " + description, List.of(), List.of()));
        }

        @Override
        public Mono<ProviderDraft> draftProvider(ProviderRequest request) {
            providerAsked.set(request);
            return draftProvider(request.description(), request.who());
        }

        @Override
        public Mono<List<PlaybookSync>> syncPlaybooks() {
            return Mono.just(synced);
        }

        @Override
        public boolean needsPlatformChecks() {
            return platformChecks;
        }

        @Override
        public String mode() {
            return "scripted";
        }
    }

    InMemorySessionStore store;
    WorkflowQueries workflows;
    RunsCommands runs;
    EventBus bus;
    List<DomainEvent> published;
    PlatformProperties props;
    MockEnvironment env;

    @BeforeEach
    void setUp() {
        store = new InMemorySessionStore();
        workflows = mock(WorkflowQueries.class);
        when(workflows.check(anyString())).thenReturn(Mono.just(List.of()));
        runs = mock(RunsCommands.class);
        when(runs.dryRun(any(), any(), any())).thenReturn(Mono.just(new DryRunResult(true, List.of("restart"), List.of(), null)));
        bus = new EventBus(CLOCK);
        published = new CopyOnWriteArrayList<>();
        bus.register(published::add);
        props = new PlatformProperties();
        env = new MockEnvironment().withProperty("server.port", "9090");
    }

    AssistantCommandHandler handler(AssistantPort agent) {
        return new AssistantCommandHandler(agent, store, workflows, runs, bus, props, CLOCK, env);
    }

    static WorkflowDraft draft(String status, String summary, String yaml) {
        return new WorkflowDraft(status, summary, yaml, List.of(), List.of(), List.of(), List.of(), new WorkflowDraft.Validation(true, 1, List.of()), null);
    }

    List<String> publishedTypes() {
        return published.stream().map(DomainEvent::type).toList();
    }

    static WorkflowDraft draft(String summary) {
        return new WorkflowDraft(WorkflowDraft.READY, summary, YAML, List.of(), List.of(), List.of(), List.of(), new WorkflowDraft.Validation(true, 1, List.of()), null);
    }

    static <T> T as(Mono<T> mono) {
        return mono.contextWrite(ctx -> CurrentIdentity.with(ctx, WHO)).block(Duration.ofSeconds(5));
    }

    static List<AssistantEvent> as(Flux<AssistantEvent> flux) {
        return flux.contextWrite(ctx -> CurrentIdentity.with(ctx, WHO)).collectList().block(Duration.ofSeconds(5));
    }

    @Test
    void aCreateSessionStreamsTheAgentsEventsAndKeepsTheDraftAndTheExchange() {
        ScriptedAgent agent = ScriptedAgent.of(
                Events.status(NOW, "Drafting"),
                Events.draft(NOW, draft("A restart with an approval.")),
                Events.message(NOW, "Here is a draft."),
                Events.done(NOW));
        AssistantCommandHandler h = handler(agent);

        String id = as(h.startSession("create", null, null));
        assertThat(id).startsWith("asst");
        AssistantSession fresh = store.get(id).orElseThrow();
        assertThat(fresh.mode()).isEqualTo(AssistantSession.CREATE);
        assertThat(fresh.status()).isEqualTo(AssistantSession.IDLE);
        assertThat(fresh.requester()).isEqualTo(WHO.id());
        assertThat(fresh.draft()).isNull();
        assertThat(published).singleElement().satisfies(e -> {
            assertThat(e.type()).isEqualTo(AssistantCommandHandler.STARTED_EVENT);
            assertThat(e.aggregateId()).isEqualTo(id);
            assertThat(e.actor()).isEqualTo(WHO.id());
            assertThat(e.data().get("mode").asText()).isEqualTo("create");
        });

        List<AssistantEvent> events = as(h.send(id, "restart a vm"));
        assertThat(events).extracting(AssistantEvent::type)
                .containsExactly(AssistantEvent.STATUS, AssistantEvent.DRAFT, AssistantEvent.MESSAGE, AssistantEvent.DONE);
        assertThat(events.get(3).data().path("status").asText()).isEqualTo("done");

        AssistantRequest request = agent.last.get();
        assertThat(request.text()).isEqualTo("restart a vm");
        assertThat(request.currentDraft()).isNull();
        assertThat(request.who()).isEqualTo(WHO);
        assertThat(request.session().id()).isEqualTo(id);
        assertThat(request.session().status()).isEqualTo(AssistantSession.WORKING);
        assertThat(request.context().requesterTeam()).isEqualTo("team-compute");
        assertThat(request.context().platformApi()).isEqualTo("http://localhost:9090");
        assertThat(request.context().owner()).isNull();
        assertThat(request.context().history()).isEmpty();

        AssistantSession after = store.get(id).orElseThrow();
        assertThat(after.status()).isEqualTo(AssistantSession.DONE);
        assertThat(after.draft().path("summary").asText()).isEqualTo("A restart with an approval.");
        assertThat(after.currentYaml()).isEqualTo(YAML);
        assertThat(after.history()).extracting(AssistantSession.Turn::role).containsExactly("user", "assistant");
        assertThat(after.history().get(1).text()).isEqualTo("Here is a draft.");
    }

    @Test
    void anEditSessionSeedsTheDraftFromTheCanvasAndPassesItToTheAgent() {
        ScriptedAgent agent = ScriptedAgent.of(Events.draft(NOW, draft("Added an approval.")), Events.message(NOW, "Done."), Events.done(NOW));
        AssistantCommandHandler h = handler(agent);

        String id = as(h.startSession("edit", null, YAML));
        AssistantSession seeded = store.get(id).orElseThrow();
        assertThat(seeded.mode()).isEqualTo(AssistantSession.EDIT);
        assertThat(seeded.workflow()).isEqualTo("restart-vm");
        assertThat(seeded.currentYaml()).isEqualTo(YAML);
        assertThat(seeded.draft().path("status").asText()).isEqualTo(WorkflowDraft.READY);
        assertThat(seeded.draft().path("validation").path("valid").asBoolean()).isTrue();

        as(h.send(id, "add an approval before the restart"));
        AssistantRequest request = agent.last.get();
        assertThat(request.currentDraft()).isEqualTo(YAML);
        assertThat(request.context().owner()).isEqualTo("team-compute");
        assertThat(request.context().folder()).isEqualTo("Compute");
        assertThat(request.context().history()).isEmpty();

        as(h.send(id, "and notify #compute-ops"));
        assertThat(agent.last.get().context().history()).extracting(AssistantSession.Turn::text)
                .containsExactly("add an approval before the restart", "Done.");
        assertThat(store.get(id).orElseThrow().history()).hasSize(4);
    }

    @Test
    void anEditSessionLoadsThePublishedWorkflowWhenNoDraftIsGiven() {
        WorkflowRecord published = WorkflowRecord.of(WorkflowYaml.parse(YAML), WorkflowStatus.PUBLISHED, "workflows/x.yaml", NOW, "seed");
        when(workflows.published("restart-vm")).thenReturn(Mono.just(published));
        when(workflows.published("nope")).thenReturn(Mono.empty());
        AssistantCommandHandler h = handler(ScriptedAgent.of(Events.done(NOW)));

        String id = as(h.startSession("edit", "restart-vm", null));
        assertThat(store.get(id).orElseThrow().currentYaml()).isEqualTo(published.yaml());

        assertThatThrownBy(() -> as(h.startSession("edit", "nope", null))).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> as(h.startSession("edit", null, null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> as(h.startSession("review", null, null))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anAgentThatNeverSaysDoneGetsADoneAppended() {
        AssistantCommandHandler h = handler(ScriptedAgent.of(Events.status(NOW, "Thinking"), Events.message(NOW, "Half way")));
        String id = as(h.startSession("create", null, null));
        List<AssistantEvent> events = as(h.send(id, "patch a vm"));
        assertThat(events).extracting(AssistantEvent::type).containsExactly(AssistantEvent.STATUS, AssistantEvent.MESSAGE, AssistantEvent.DONE);
        assertThat(Events.isFailed(events.get(2))).isFalse();
        assertThat(store.get(id).orElseThrow().status()).isEqualTo(AssistantSession.DONE);
    }

    @Test
    void aFailingAgentEndsTheStreamWithAMessageAndAFailedDone() {
        AssistantCommandHandler h = handler(new ScriptedAgent(r -> Flux.error(new IllegalStateException("devin is down"))));
        String id = as(h.startSession("create", null, null));
        StepVerifier.create(h.send(id, "delete a vm").contextWrite(ctx -> CurrentIdentity.with(ctx, WHO)))
                .assertNext(e -> {
                    assertThat(e.type()).isEqualTo(AssistantEvent.MESSAGE);
                    assertThat(Events.textOf(e)).contains("devin is down");
                })
                .assertNext(e -> {
                    assertThat(e.type()).isEqualTo(AssistantEvent.DONE);
                    assertThat(Events.isFailed(e)).isTrue();
                    assertThat(e.data().path("error").asText()).isEqualTo("devin is down");
                })
                .verifyComplete();
        assertThat(store.get(id).orElseThrow().status()).isEqualTo(AssistantSession.FAILED);
    }

    @Test
    void nothingAfterDoneIsForwarded() {
        AssistantCommandHandler h = handler(ScriptedAgent.of(Events.done(NOW), Events.message(NOW, "late")));
        String id = as(h.startSession("create", null, null));
        List<AssistantEvent> events = as(h.send(id, "x"));
        assertThat(events).extracting(AssistantEvent::type).containsExactly(AssistantEvent.DONE);
        assertThat(store.get(id).orElseThrow().history()).extracting(AssistantSession.Turn::role).containsExactly("user");
    }

    @Test
    void theAgentsOwnSessionIdIsKeptForFollowUps() {
        AssistantCommandHandler h = handler(ScriptedAgent.of(Events.status(NOW, "Devin session devin-1 started", "devin-1"), Events.done(NOW)));
        String id = as(h.startSession("create", null, null));
        List<AssistantEvent> events = as(h.send(id, "x"));
        assertThat(events.get(0).data().path("externalId").asText()).isEqualTo("devin-1");
        assertThat(store.get(id).orElseThrow().externalId()).isEqualTo("devin-1");
    }

    @Test
    void anAgentSessionThatEndedIsForgottenSoTheNextMessageStartsANewOne() {
        AssistantCommandHandler h = handler(ScriptedAgent.of(Events.status(NOW, "Devin session devin-1 started", "devin-1"),
                Events.message(NOW, "Devin did not finish within 30 minutes"), Events.sessionEnded(NOW, "Ending Devin session devin-1"),
                Events.failed(NOW, "timeout after PT30M")));
        String id = as(h.startSession("create", null, null));

        as(h.send(id, "x"));

        assertThat(store.get(id).orElseThrow().externalId()).isNull();
        assertThat(store.get(id).orElseThrow().status()).isEqualTo(AssistantSession.FAILED);
    }

    @Test
    void aClientThatLeavesMidStreamClosesTheSessionAndForgetsTheAgentsOwn() throws Exception {
        AssistantCommandHandler h = handler(new ScriptedAgent(r -> Flux.concat(Flux.just(Events.status(NOW, "Devin session devin-7 started", "devin-7")), Flux.never())));
        String id = as(h.startSession("create", null, null));

        h.send(id, "take your time").take(1).contextWrite(ctx -> CurrentIdentity.with(ctx, WHO)).blockLast(Duration.ofSeconds(5));

        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!AssistantSession.DONE.equals(store.get(id).orElseThrow().status()) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(store.get(id).orElseThrow().status()).isEqualTo(AssistantSession.DONE);
        assertThat(store.get(id).orElseThrow().externalId()).as("the agent ends its session on the cancel").isNull();
    }

    @Test
    void unknownSessionsAndBlankMessagesAreRejected() {
        AssistantCommandHandler h = handler(ScriptedAgent.of(Events.done(NOW)));
        assertThatThrownBy(() -> as(h.send("asst-missing", "x"))).isInstanceOf(NotFoundException.class);
        String id = as(h.startSession("create", null, null));
        assertThatThrownBy(() -> as(h.send(id, "  "))).isInstanceOf(IllegalArgumentException.class);
        assertThat(store.get(id).orElseThrow().status()).isEqualTo(AssistantSession.IDLE);
    }

    @Test
    void aSessionIsItsOwnersAnAdminMayStepInAndAServiceAccountMayNot() {
        AssistantCommandHandler h = handler(ScriptedAgent.of(Events.done(NOW)));
        String id = as(h.startSession("create", null, null));

        StepVerifier.create(h.send(id, "x").contextWrite(ctx -> CurrentIdentity.with(ctx, OTHER)))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(ForbiddenException.class).hasMessageContaining(id).hasMessageContaining("u-ada"))
                .verify();
        StepVerifier.create(h.send(id, "x").contextWrite(ctx -> CurrentIdentity.with(ctx, ROBOT))).expectError(ForbiddenException.class).verify();
        assertThat(store.get(id).orElseThrow().history()).isEmpty();
        assertThat(store.get(id).orElseThrow().status()).isEqualTo(AssistantSession.IDLE);

        assertThat(h.send(id, "x").contextWrite(ctx -> CurrentIdentity.with(ctx, ADMIN)).collectList().block(Duration.ofSeconds(5))).hasSize(1);
        assertThat(store.get(id).orElseThrow().history()).extracting(AssistantSession.Turn::text).containsExactly("x");
        assertThat(as(h.send(id, "y"))).hasSize(1);
    }

    /** A store that notes every call made on a non-blocking thread, where the JDBC store would stall the event loop. */
    static final class ThreadCheckingStore implements com.iaf.platform.assistant.ports.outbound.SessionStore {
        final InMemorySessionStore inner = new InMemorySessionStore();
        final List<String> offenders = new CopyOnWriteArrayList<>();

        private void check(String call) {
            if (reactor.core.scheduler.Schedulers.isInNonBlockingThread()) {
                offenders.add(call + " on " + Thread.currentThread().getName());
            }
        }

        @Override
        public void save(AssistantSession session) {
            check("save");
            inner.save(session);
        }

        @Override
        public java.util.Optional<AssistantSession> get(String id) {
            check("get");
            return inner.get(id);
        }

        @Override
        public java.util.Optional<AssistantSession> update(String id, java.util.function.UnaryOperator<AssistantSession> change) {
            check("update");
            return inner.update(id, change);
        }

        @Override
        public int deleteUpdatedBefore(java.time.Instant cutoff) {
            check("deleteUpdatedBefore");
            return inner.deleteUpdatedBefore(cutoff);
        }
    }

    @Test
    void theSessionIsRecordedOffTheAgentsThreadsAndClosedOffTheCancellingOne() throws Exception {
        ThreadCheckingStore checking = new ThreadCheckingStore();
        ScriptedAgent agent = new ScriptedAgent(r -> Flux.just(Events.status(NOW, "Devin session devin-1 started", "devin-1"),
                Events.draft(NOW, draft("A restart.")), Events.message(NOW, "Here."), Events.done(NOW)).publishOn(reactor.core.scheduler.Schedulers.parallel()));
        AssistantCommandHandler h = new AssistantCommandHandler(agent, checking, workflows, runs, bus, props, CLOCK, env);
        String id = as(h.startSession("create", null, null));

        List<AssistantEvent> events = as(h.send(id, "restart a vm"));

        assertThat(events).hasSize(4);
        assertThat(checking.inner.get(id).orElseThrow().status()).isEqualTo(AssistantSession.DONE);
        assertThat(checking.inner.get(id).orElseThrow().externalId()).isEqualTo("devin-1");
        assertThat(checking.offenders).isEmpty();

        // the client leaves while the agent is still working: the cancel arrives on a timer thread
        AssistantCommandHandler never = new AssistantCommandHandler(new ScriptedAgent(r -> Flux.never()), checking, workflows, runs, bus, props, CLOCK, env);
        String left = as(never.startSession("create", null, null));
        never.send(left, "take your time").take(Duration.ofMillis(100)).contextWrite(ctx -> CurrentIdentity.with(ctx, WHO)).blockLast(Duration.ofSeconds(5));
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!AssistantSession.DONE.equals(checking.inner.get(left).orElseThrow().status()) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(checking.inner.get(left).orElseThrow().status()).isEqualTo(AssistantSession.DONE);
        assertThat(checking.offenders).isEmpty();
    }

    @Test
    void draftWorkflowRunsAFreshSessionToCompletionAndReturnsTheFinalDraft() {
        AssistantCommandHandler h = handler(ScriptedAgent.of(Events.draft(NOW, draft("first")), Events.draft(NOW, draft("final")), Events.done(NOW)));
        JsonNode payload = as(h.draftWorkflow("restart a vm"));
        assertThat(payload.path("summary").asText()).isEqualTo("final");
        assertThat(payload.path("workflowYaml").asText()).isEqualTo(YAML);

        JsonNode none = as(handler(ScriptedAgent.of(Events.message(NOW, "nothing"), Events.done(NOW))).draftWorkflow("x"));
        assertThat(none.path("status").asText()).isEqualTo(WorkflowDraft.FAILED);
        assertThatThrownBy(() -> as(h.draftWorkflow(" "))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void draftProviderGoesStraightToTheAgentWithTheCaller() {
        ProviderDraft d = as(handler(ScriptedAgent.of(Events.done(NOW))).draftProvider("  the backup service "));
        assertThat(d.summary()).isEqualTo("for u-ada: the backup service");
        assertThat(d.yaml()).startsWith("kind: Provider");
    }

    // ---------------------------------------------------------------- the platform's own checks

    static final Problem UNKNOWN_ACTION = Problem.error("provider.action-unknown", "restart", "with.action", "vsphere has no action vm.reboot");

    @Test
    void theDraftCarriesThePlatformsValidationAndDryRunInsteadOfTheAgents() {
        WorkflowDraft claimed = new WorkflowDraft(WorkflowDraft.READY, "A restart.", YAML, List.of(), List.of(), List.of(), List.of(),
                new WorkflowDraft.Validation(true, 4, List.of()), new WorkflowDraft.DryRun(true, List.of("made", "up"), null));
        ScriptedAgent agent = ScriptedAgent.of(Events.status(NOW, "Devin session devin-1 started", "devin-1"), Events.draft(NOW, claimed),
                Events.validation(NOW, List.of(Problem.error("devin", null, null, "Devin's own view"))), Events.message(NOW, "Here it is."), Events.done(NOW))
                .checkedByThePlatform();
        AssistantCommandHandler h = handler(agent);
        String id = as(h.startSession("create", null, null));

        List<AssistantEvent> events = as(h.send(id, "restart a vm"));

        assertThat(events).extracting(AssistantEvent::type).containsExactly(
                AssistantEvent.STATUS, AssistantEvent.DRAFT, AssistantEvent.VALIDATION, AssistantEvent.MESSAGE, AssistantEvent.DONE);
        JsonNode draft = events.get(1).data();
        assertThat(draft.path("status").asText()).isEqualTo(WorkflowDraft.READY);
        assertThat(draft.path("validation").path("valid").asBoolean()).isTrue();
        assertThat(draft.path("validation").path("rounds").asInt()).isEqualTo(1);
        assertThat(draft.path("dryRun").path("passed").asBoolean()).isTrue();
        assertThat(draft.path("dryRun").path("path")).extracting(JsonNode::asText).containsExactly("restart");
        assertThat(events.get(2).data().path("valid").asBoolean()).isTrue();
        assertThat(events.get(2).data().path("problems")).isEmpty();
        verify(runs).dryRun(any(), eq(Schemas.sample(WorkflowYaml.parse(YAML).inputSchema())), eq(WHO));
        assertThat(store.get(id).orElseThrow().draft().path("dryRun").path("path")).extracting(JsonNode::asText).containsExactly("restart");
        assertThat(agent.asked).hasSize(1);
    }

    @Test
    void errorsTheAgentMissedGoBackOnItsSessionUntilTheyAreFixed() {
        when(workflows.check(BAD_YAML)).thenReturn(Mono.just(List.of(UNKNOWN_ACTION, Problem.warning("style", "restart", null, "a warning stays here"))));
        ScriptedAgent agent = new ScriptedAgent(r -> r.fromPlatform()
                ? Flux.just(Events.draft(NOW, draft(WorkflowDraft.READY, "fixed", YAML)), Events.done(NOW))
                : Flux.just(Events.status(NOW, "Devin session devin-1 started", "devin-1"), Events.draft(NOW, draft(WorkflowDraft.READY, "first", BAD_YAML)), Events.done(NOW)))
                .checkedByThePlatform();
        AssistantCommandHandler h = handler(agent);
        String id = as(h.startSession("create", null, null));

        List<AssistantEvent> events = as(h.send(id, "restart a vm"));

        assertThat(events).extracting(AssistantEvent::type).containsExactly(
                AssistantEvent.STATUS,          // Devin session started
                AssistantEvent.DRAFT,           // called ready, but not by the platform
                AssistantEvent.VALIDATION,      // the platform's problems, on their steps
                AssistantEvent.STATUS,          // sending them back, round 1 of 3
                AssistantEvent.DRAFT,           // fixed
                AssistantEvent.VALIDATION,
                AssistantEvent.DONE);
        assertThat(events.get(1).data().path("status").asText()).isEqualTo(WorkflowDraft.WORKING);
        assertThat(events.get(1).data().path("validation").path("errors")).extracting(JsonNode::asText)
                .containsExactly("provider.action-unknown @restart (with.action): vsphere has no action vm.reboot");
        assertThat(events.get(1).data().has("dryRun")).isFalse();
        JsonNode problems = events.get(2).data().path("problems");
        assertThat(problems).hasSize(2);
        assertThat(problems.get(0).path("step").asText()).isEqualTo("restart");
        assertThat(problems.get(0).path("field").asText()).isEqualTo("with.action");
        assertThat(events.get(3).data().path("status").asText()).contains("round 1 of 3");
        assertThat(events.get(4).data().path("status").asText()).isEqualTo(WorkflowDraft.READY);
        assertThat(events.get(4).data().path("validation").path("rounds").asInt()).isEqualTo(2);
        assertThat(Events.isFailed(events.get(6))).isFalse();

        assertThat(agent.asked).hasSize(2);
        AssistantRequest followUp = agent.asked.get(1);
        assertThat(followUp.fromPlatform()).isTrue();
        assertThat(followUp.session().externalId()).isEqualTo("devin-1");
        assertThat(followUp.currentDraft()).isEqualTo(BAD_YAML);
        assertThat(followUp.text()).startsWith("PLATFORM_CHECKS (round 1 of 3):\n")
                .contains("- provider.action-unknown at step restart (with.action): vsphere has no action vm.reboot\n")
                .doesNotContain("a warning stays here");
        assertThat(store.get(id).orElseThrow().draft().path("summary").asText()).isEqualTo("fixed");
        assertThat(store.get(id).orElseThrow().externalId()).isEqualTo("devin-1");
    }

    @Test
    void afterTheLastRoundTheDraftFailsWithThePlatformsProblems() {
        props.getAssistant().setMaxRounds(1);
        when(workflows.check(BAD_YAML)).thenReturn(Mono.just(List.of(UNKNOWN_ACTION)));
        ScriptedAgent agent = new ScriptedAgent(r -> Flux.just(Events.status(NOW, "Devin session devin-1 started", "devin-1"),
                Events.draft(NOW, draft(WorkflowDraft.READY, "stubborn", BAD_YAML)), Events.done(NOW))).checkedByThePlatform();
        AssistantCommandHandler h = handler(agent);
        String id = as(h.startSession("create", null, null));

        List<AssistantEvent> events = as(h.send(id, "restart a vm"));

        assertThat(agent.asked).hasSize(2);
        assertThat(agent.asked.get(1).text()).startsWith("PLATFORM_CHECKS (round 1 of 1):");
        AssistantEvent lastDraft = events.stream().filter(e -> AssistantEvent.DRAFT.equals(e.type())).reduce((a, b) -> b).orElseThrow();
        assertThat(lastDraft.data().path("status").asText()).isEqualTo(WorkflowDraft.FAILED);
        assertThat(events.get(events.size() - 2).type()).isEqualTo(AssistantEvent.MESSAGE);
        assertThat(Events.textOf(events.get(events.size() - 2))).contains("still find 1 error after 1 round")
                .contains("provider.action-unknown @restart (with.action)");
        assertThat(Events.isFailed(events.get(events.size() - 1))).isFalse();
        assertThat(store.get(id).orElseThrow().draft().path("status").asText()).isEqualTo(WorkflowDraft.FAILED);
        assertThat(publishedTypes()).doesNotContain(AssistantCommandHandler.READY_EVENT);
    }

    @Test
    void aDryRunFailureIsAProblemOnTheStepThatFailed() {
        props.getAssistant().setMaxRounds(0);
        when(runs.dryRun(any(), any(), any())).thenReturn(Mono.just(new DryRunResult(false, List.of("restart"),
                List.of(new DryRunResult.DryRunStep("restart", "Restart VM", "failed", null, null, "provider.error: boom")), "restart: provider.error: boom")));
        ScriptedAgent agent = ScriptedAgent.of(Events.draft(NOW, draft(WorkflowDraft.READY, "x", YAML)), Events.done(NOW)).checkedByThePlatform();
        AssistantCommandHandler h = handler(agent);
        String id = as(h.startSession("create", null, null));

        List<AssistantEvent> events = as(h.send(id, "restart a vm"));

        JsonNode draft = events.get(0).data();
        assertThat(draft.path("validation").path("valid").asBoolean()).isTrue();
        assertThat(draft.path("dryRun").path("passed").asBoolean()).isFalse();
        assertThat(draft.path("dryRun").path("error").asText()).isEqualTo("provider.error: boom");
        assertThat(draft.path("status").asText()).isEqualTo(WorkflowDraft.FAILED);
        JsonNode problem = events.get(1).data().path("problems").get(0);
        assertThat(problem.path("code").asText()).isEqualTo("dry-run.failed");
        assertThat(problem.path("step").asText()).isEqualTo("restart");
        assertThat(events.get(1).data().path("valid").asBoolean()).isFalse();
        assertThat(agent.asked).hasSize(1);
        assertThat(store.get(id).orElseThrow().draft().path("status").asText()).isEqualTo(WorkflowDraft.FAILED);
    }

    @Test
    void draftsThatWaitForTheUserOrThatTheAgentGaveUpOnAreNotSentBack() {
        when(workflows.check(BAD_YAML)).thenReturn(Mono.just(List.of(UNKNOWN_ACTION)));
        ScriptedAgent asking = ScriptedAgent.of(Events.draft(NOW, draft(WorkflowDraft.NEEDS_INPUT, "which one?", BAD_YAML)), Events.done(NOW)).checkedByThePlatform();
        AssistantCommandHandler h = handler(asking);
        as(h.send(as(h.startSession("create", null, null)), "restart a vm"));
        assertThat(asking.asked).hasSize(1);

        ScriptedAgent failing = ScriptedAgent.of(Events.draft(NOW, draft(WorkflowDraft.READY, "x", BAD_YAML)), Events.failed(NOW, "session error")).checkedByThePlatform();
        AssistantCommandHandler h2 = handler(failing);
        List<AssistantEvent> events = as(h2.send(as(h2.startSession("create", null, null)), "restart a vm"));
        assertThat(failing.asked).hasSize(1);
        assertThat(Events.isFailed(events.get(events.size() - 1))).isTrue();
    }

    @Test
    void theDemoAgentsDraftsAreTakenAsTheyAre() {
        ScriptedAgent agent = ScriptedAgent.of(Events.draft(NOW, draft(WorkflowDraft.READY, "x", BAD_YAML)), Events.validation(NOW, List.of()), Events.done(NOW));
        AssistantCommandHandler h = handler(agent);
        List<AssistantEvent> events = as(h.send(as(h.startSession("create", null, null)), "restart a vm"));
        assertThat(events).extracting(AssistantEvent::type).containsExactly(AssistantEvent.DRAFT, AssistantEvent.VALIDATION, AssistantEvent.DONE);
        verify(workflows, never()).check(BAD_YAML);
        verify(runs, never()).dryRun(any(), any(), any());
    }

    // ---------------------------------------------------------------- events for the audit stream

    @Test
    void aSessionPublishesStartedMessageDraftUpdatedAndReadyWithTheDraftAndTheUser() {
        ScriptedAgent agent = ScriptedAgent.of(Events.status(NOW, "Devin session devin-1 started", "devin-1"), Events.draft(NOW, draft("A restart.")),
                Events.message(NOW, "Here is a draft."), Events.done(NOW));
        AssistantCommandHandler h = handler(agent);
        String id = as(h.startSession("create", null, null));
        as(h.send(id, "restart a vm"));

        assertThat(publishedTypes()).containsExactly(
                AssistantCommandHandler.STARTED_EVENT,
                AssistantCommandHandler.MESSAGE_EVENT,          // the user's
                AssistantCommandHandler.DRAFT_UPDATED_EVENT,
                AssistantCommandHandler.MESSAGE_EVENT,          // the assistant's
                AssistantCommandHandler.READY_EVENT);
        assertThat(published).allSatisfy(e -> {
            assertThat(e.aggregate()).isEqualTo(AssistantCommandHandler.AGGREGATE);
            assertThat(e.aggregateId()).isEqualTo(id);
            assertThat(e.actor()).isEqualTo(WHO.id());
            assertThat(e.data().path("draftId").asText()).isEqualTo(id);
            assertThat(e.data().path("user").asText()).isEqualTo(WHO.id());
            assertThat(e.data().path("kind").asText()).isEqualTo("workflow-draft");
        });
        assertThat(published.get(0).data().path("mode").asText()).isEqualTo("create");
        assertThat(published.get(1).data().path("role").asText()).isEqualTo("user");
        assertThat(published.get(1).data().path("text").asText()).isEqualTo("restart a vm");
        assertThat(published.get(2).data().path("status").asText()).isEqualTo(WorkflowDraft.READY);
        assertThat(published.get(2).data().path("contentHash").asText()).isEqualTo(WorkflowYaml.sha256(YAML));
        assertThat(published.get(2).data().path("externalId").asText()).isEqualTo("devin-1");
        assertThat(published.get(3).data().path("role").asText()).isEqualTo("assistant");
        assertThat(published.get(4).data().path("contentHash").asText()).isEqualTo(WorkflowYaml.sha256(YAML));
        assertThat(published).noneSatisfy(e -> assertThat(e.data().toString()).contains("kind: Workflow"));
    }

    @Test
    void aSessionThatEndsWithoutAReadyDraftPublishesNoReady() {
        AssistantCommandHandler h = handler(ScriptedAgent.of(Events.draft(NOW, draft(WorkflowDraft.NEEDS_INPUT, "which?", YAML)), Events.done(NOW)));
        as(h.send(as(h.startSession("create", null, null)), "x"));
        assertThat(publishedTypes()).contains(AssistantCommandHandler.DRAFT_UPDATED_EVENT).doesNotContain(AssistantCommandHandler.READY_EVENT);
    }

    @Test
    void aProviderDraftIsTaggedWithItsOwnDraftIdAndPublishesItsEvents() {
        props.getAssistant().setPlatformApiUrl("https://platform.example.com/");
        ScriptedAgent agent = ScriptedAgent.of(Events.done(NOW));
        as(handler(agent).draftProvider("the backup service"));

        ProviderRequest asked = agent.providerAsked.get();
        assertThat(asked.draftId()).startsWith("pdraft_");
        assertThat(asked.context().platformApi()).isEqualTo("https://platform.example.com");
        assertThat(asked.context().owner()).isEqualTo("team-compute");
        assertThat(publishedTypes()).containsExactly(AssistantCommandHandler.STARTED_EVENT, AssistantCommandHandler.DRAFT_UPDATED_EVENT, AssistantCommandHandler.READY_EVENT);
        assertThat(published).allSatisfy(e -> {
            assertThat(e.aggregateId()).isEqualTo(asked.draftId());
            assertThat(e.data().path("kind").asText()).isEqualTo("provider-draft");
            assertThat(e.data().path("user").asText()).isEqualTo(WHO.id());
        });
    }

    @Test
    void onlyAnAdminSyncsThePlaybooks() {
        ScriptedAgent agent = ScriptedAgent.of(Events.done(NOW));
        agent.synced = List.of(new PlaybookSync("nl-builder-playbook.md", "!workflow-draft", "playbook-1", "created"));
        AssistantCommandHandler h = handler(agent);

        assertThatThrownBy(() -> as(h.syncPlaybooks())).isInstanceOf(ForbiddenException.class);
        assertThat(published).isEmpty();

        Identity admin = new Identity("u-root", "Root", null, List.of(), List.of(), true, Identity.Kind.USER);
        List<PlaybookSync> result = h.syncPlaybooks().contextWrite(ctx -> CurrentIdentity.with(ctx, admin)).block(Duration.ofSeconds(5));
        assertThat(result).containsExactly(new PlaybookSync("nl-builder-playbook.md", "!workflow-draft", "playbook-1", "created"));
        assertThat(publishedTypes()).containsExactly(AssistantCommandHandler.PLAYBOOKS_SYNCED_EVENT);
        assertThat(published.get(0).data().path("playbooks").get(0).path("action").asText()).isEqualTo("created");
    }

    @Test
    void theConfiguredPlatformApiIsWhatTheAgentCallsBack() {
        props.getAssistant().setPlatformApiUrl("https://platform.example.com");
        ScriptedAgent agent = ScriptedAgent.of(Events.done(NOW));
        AssistantCommandHandler h = handler(agent);
        as(h.send(as(h.startSession("create", null, null)), "x"));
        assertThat(agent.last.get().context().platformApi()).isEqualTo("https://platform.example.com");
    }
}
