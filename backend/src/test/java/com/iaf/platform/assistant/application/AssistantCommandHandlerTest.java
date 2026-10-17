package com.iaf.platform.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.identity.application.CurrentIdentity;
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

    /** An agent that answers with a scripted stream and remembers what it was asked. */
    static final class ScriptedAgent implements AssistantPort {
        final AtomicReference<AssistantRequest> last = new AtomicReference<>();
        final Function<AssistantRequest, Flux<AssistantEvent>> script;

        ScriptedAgent(Function<AssistantRequest, Flux<AssistantEvent>> script) {
            this.script = script;
        }

        static ScriptedAgent of(AssistantEvent... events) {
            return new ScriptedAgent(r -> Flux.just(events));
        }

        @Override
        public Flux<AssistantEvent> run(AssistantRequest request) {
            last.set(request);
            return script.apply(request);
        }

        @Override
        public Mono<ProviderDraft> draftProvider(String description, Identity who) {
            return Mono.just(new ProviderDraft("kind: Provider\n", "for " + who.id() + ": " + description, List.of(), List.of()));
        }

        @Override
        public String mode() {
            return "scripted";
        }
    }

    InMemorySessionStore store;
    WorkflowQueries workflows;
    MockEnvironment env;

    @BeforeEach
    void setUp() {
        store = new InMemorySessionStore();
        workflows = mock(WorkflowQueries.class);
        when(workflows.check(anyString())).thenReturn(Mono.just(List.of()));
        env = new MockEnvironment().withProperty("server.port", "9090");
    }

    AssistantCommandHandler handler(AssistantPort agent) {
        return new AssistantCommandHandler(agent, store, workflows, CLOCK, env);
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
    void unknownSessionsAndBlankMessagesAreRejected() {
        AssistantCommandHandler h = handler(ScriptedAgent.of(Events.done(NOW)));
        assertThatThrownBy(() -> as(h.send("asst-missing", "x"))).isInstanceOf(NotFoundException.class);
        String id = as(h.startSession("create", null, null));
        assertThatThrownBy(() -> as(h.send(id, "  "))).isInstanceOf(IllegalArgumentException.class);
        assertThat(store.get(id).orElseThrow().status()).isEqualTo(AssistantSession.IDLE);
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
}
