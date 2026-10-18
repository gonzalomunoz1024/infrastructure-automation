package com.iaf.platform.assistant.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.assistant.domain.AssistantEvent;
import com.iaf.platform.assistant.domain.AssistantSession;
import com.iaf.platform.assistant.domain.Events;
import com.iaf.platform.assistant.domain.ProviderDraft;
import com.iaf.platform.assistant.domain.WorkflowDraft;
import com.iaf.platform.assistant.ports.inbound.AssistantCommands;
import com.iaf.platform.assistant.ports.outbound.AssistantPort;
import com.iaf.platform.assistant.ports.outbound.AssistantPort.AssistantRequest;
import com.iaf.platform.assistant.ports.outbound.AssistantPort.Context;
import com.iaf.platform.assistant.ports.outbound.SessionStore;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.domain.Ids;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.common.domain.Problem;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.identity.application.CurrentIdentity;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowRecord;
import com.iaf.platform.workflows.domain.WorkflowYaml;
import com.iaf.platform.workflows.ports.inbound.WorkflowQueries;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Builder sessions: start one, send a message and stream the agent's events while the session
 * keeps the latest draft and the exchange, and the one-shot drafts the MCP tool and the provider
 * wizard use. The agent behind it is whatever {@link AssistantPort} is wired.
 */
@Slf4j
@Primary
@Service
@RequiredArgsConstructor
public class AssistantCommandHandler implements AssistantCommands {

    /** {@code assistant.session-started}: {sessionId, mode, workflow, by}, recorded on the platform audit stream. */
    public static final String SESSION_STARTED = "assistant.session-started";

    private final AssistantPort agent;
    private final SessionStore sessions;
    private final WorkflowQueries workflows;
    private final Clock clock;
    private final Environment environment;
    private final EventBus events;

    @Override
    public Mono<String> startSession(String mode, String workflow, String draftYaml) {
        return CurrentIdentity.current().flatMap(who -> {
            String m = mode == null || mode.isBlank() ? AssistantSession.CREATE : mode.trim().toLowerCase();
            if (!AssistantSession.CREATE.equals(m) && !AssistantSession.EDIT.equals(m)) {
                throw new IllegalArgumentException("mode is create or edit, was '" + mode + "'");
            }
            Instant now = clock.instant();
            String id = Ids.next("asst");
            if (AssistantSession.CREATE.equals(m)) {
                sessions.save(new AssistantSession(id, m, blankToNull(workflow), who.id(), AssistantSession.IDLE, null, List.of(), null, now, now));
                started(id, m, blankToNull(workflow), who);
                return Mono.just(id);
            }
            return seedYaml(workflow, draftYaml).flatMap(yaml -> workflows.check(yaml).defaultIfEmpty(List.of()).map(problems -> {
                String name = blankToNull(workflow);
                if (name == null) {
                    try {
                        name = WorkflowYaml.parse(yaml).name();
                    } catch (IllegalArgumentException e) {
                        name = null;
                    }
                }
                sessions.save(new AssistantSession(id, m, name, who.id(), AssistantSession.IDLE, seedDraft(yaml, problems), List.of(), null, now, now));
                started(id, m, name, who);
                return id;
            }));
        });
    }

    private void started(String id, String mode, String workflow, Identity who) {
        ObjectNode data = JsonNodeFactory.instance.objectNode().put("sessionId", id).put("mode", mode).put("by", who.id());
        if (workflow != null) {
            data.put("workflow", workflow);
        }
        events.publish(SESSION_STARTED, "assistant-session", id, who.id(), data);
    }

    /** Edit mode starts from the YAML on the canvas, or from the published version of the named workflow. */
    private Mono<String> seedYaml(String workflow, String draftYaml) {
        if (draftYaml != null && !draftYaml.isBlank()) {
            return Mono.just(draftYaml);
        }
        if (workflow == null || workflow.isBlank()) {
            return Mono.error(new IllegalArgumentException("edit mode needs the workflow name or the draft YAML"));
        }
        return workflows.published(workflow.trim()).map(WorkflowRecord::yaml)
                .switchIfEmpty(Mono.error(new NotFoundException("Published workflow", workflow.trim())));
    }

    private static JsonNode seedDraft(String yaml, List<Problem> problems) {
        boolean valid = problems.stream().noneMatch(Problem::isError);
        List<String> errors = problems.stream().filter(Problem::isError).map(p -> p.code() + ": " + p.message()).toList();
        return new WorkflowDraft(valid ? WorkflowDraft.READY : WorkflowDraft.WORKING, "The draft loaded from the canvas, not yet changed.", yaml,
                List.of(), List.of(), List.of(), List.of(), new WorkflowDraft.Validation(valid, 1, errors), null).toJson();
    }

    @Override
    public Flux<AssistantEvent> send(String sessionId, String text) {
        return CurrentIdentity.current().flatMapMany(who -> {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("text is required");
            }
            AssistantSession session = sessions.get(sessionId).orElseThrow(() -> new NotFoundException("Assistant session", sessionId));
            Instant now = clock.instant();
            String message = text.trim();
            AssistantSession working = session.withTurn("user", message, now).withStatus(AssistantSession.WORKING, now);
            sessions.save(working);
            String current = working.currentYaml();
            AssistantRequest request = new AssistantRequest(working, message, current, who, context(session, current, who));
            AtomicBoolean ended = new AtomicBoolean();
            Flux<AssistantEvent> stream = Flux.defer(() -> agent.run(request))
                    .onErrorResume(e -> {
                        log.warn("assistant {} failed on session {}: {}", agent.mode(), sessionId, e.toString());
                        return Flux.just(Events.message(clock.instant(), "The assistant failed: " + e.getMessage()), Events.failed(clock.instant(), e.getMessage()));
                    })
                    .takeUntil(Events::isDone)
                    .doOnNext(e -> record(sessionId, e, ended));
            return stream.concatWith(Flux.defer(() -> {
                if (ended.get()) {
                    return Flux.empty();
                }
                AssistantEvent done = Events.done(clock.instant());
                record(sessionId, done, ended);
                return Flux.just(done);
            })).doOnCancel(() -> sessions.update(sessionId, s -> AssistantSession.WORKING.equals(s.status()) ? s.withStatus(AssistantSession.DONE, clock.instant()) : s));
        });
    }

    /** Keep the session in step with the stream: the latest draft, the assistant's words, the agent's own id, the end. */
    private void record(String sessionId, AssistantEvent e, AtomicBoolean ended) {
        Instant now = clock.instant();
        switch (e.type()) {
            case AssistantEvent.DRAFT -> sessions.update(sessionId, s -> s.withDraft(e.data(), now));
            case AssistantEvent.MESSAGE -> {
                String t = Events.textOf(e);
                if (!t.isBlank()) {
                    sessions.update(sessionId, s -> s.withTurn("assistant", t, now));
                }
            }
            case AssistantEvent.DONE -> {
                ended.set(true);
                String status = Events.isFailed(e) ? AssistantSession.FAILED : AssistantSession.DONE;
                sessions.update(sessionId, s -> s.withStatus(status, now));
            }
            default -> {
            }
        }
        if (e.data() != null && e.data().hasNonNull("externalId")) {
            String external = e.data().get("externalId").asText();
            sessions.update(sessionId, s -> s.withExternalId(external, now));
        }
    }

    /** Owner and folder from the draft being edited, the requester's team, and where the platform API is. */
    private Context context(AssistantSession before, String currentYaml, Identity who) {
        String owner = null;
        String folder = null;
        if (currentYaml != null) {
            try {
                WorkflowDefinition def = WorkflowYaml.parse(currentYaml);
                owner = def.metadata().owner();
                folder = def.metadata().folder();
            } catch (IllegalArgumentException e) {
                log.debug("current draft does not parse: {}", e.getMessage());
            }
        }
        String team = who == null || who.teams() == null || who.teams().isEmpty() ? null : who.teams().get(0);
        String port = environment == null ? "8080" : environment.getProperty("server.port", "8080");
        return new Context(owner, folder, team, "http://localhost:" + port, before.history() == null ? List.of() : before.history());
    }

    @Override
    public Mono<JsonNode> draftWorkflow(String description) {
        if (description == null || description.isBlank()) {
            return Mono.error(new IllegalArgumentException("description is required"));
        }
        return startSession(AssistantSession.CREATE, null, null)
                .flatMap(id -> send(id, description).then(Mono.fromSupplier(() -> sessions.get(id).map(AssistantSession::draft).orElse(null))))
                .defaultIfEmpty(WorkflowDraft.failed("The assistant produced no draft.", "no draft event was received").toJson());
    }

    @Override
    public Mono<ProviderDraft> draftProvider(String description) {
        if (description == null || description.isBlank()) {
            return Mono.error(new IllegalArgumentException("description is required"));
        }
        return CurrentIdentity.current().flatMap(who -> agent.draftProvider(description.trim(), who));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
