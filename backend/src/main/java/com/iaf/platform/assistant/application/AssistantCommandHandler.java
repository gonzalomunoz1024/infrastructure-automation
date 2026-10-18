package com.iaf.platform.assistant.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.assistant.domain.AssistantEvent;
import com.iaf.platform.assistant.domain.AssistantSession;
import com.iaf.platform.assistant.domain.DraftReview;
import com.iaf.platform.assistant.domain.Events;
import com.iaf.platform.assistant.domain.ProviderDraft;
import com.iaf.platform.assistant.domain.WorkflowDraft;
import com.iaf.platform.assistant.domain.Words;
import com.iaf.platform.assistant.ports.inbound.AssistantCommands;
import com.iaf.platform.assistant.ports.outbound.AssistantPort;
import com.iaf.platform.assistant.ports.outbound.AssistantPort.AssistantRequest;
import com.iaf.platform.assistant.ports.outbound.AssistantPort.Context;
import com.iaf.platform.assistant.ports.outbound.AssistantPort.PlaybookSync;
import com.iaf.platform.assistant.ports.outbound.AssistantPort.ProviderRequest;
import com.iaf.platform.assistant.ports.outbound.SessionStore;
import com.iaf.platform.common.domain.ForbiddenException;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.domain.Ids;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.common.domain.Problem;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.common.schema.Schemas;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.identity.application.CurrentIdentity;
import com.iaf.platform.runs.domain.DryRunResult;
import com.iaf.platform.runs.ports.inbound.RunsCommands;
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
 * wizard use. The agent behind it is whatever {@link AssistantPort} is wired. When the agent does
 * not check its drafts with the platform (Devin), the platform checks every draft update itself,
 * shows its own problems on the steps they concern, and sends what is left back to the agent for
 * up to {@code platform.assistant.max-rounds} rounds. Every session publishes
 * {@code assistant.started}, {@code assistant.message}, {@code assistant.draft-updated} and
 * {@code assistant.ready} for the audit stream.
 */
@Slf4j
@Primary
@Service
@RequiredArgsConstructor
public class AssistantCommandHandler implements AssistantCommands {

    public static final String AGGREGATE = "assistant-session";
    public static final String STARTED_EVENT = "assistant.started";
    public static final String MESSAGE_EVENT = "assistant.message";
    public static final String DRAFT_UPDATED_EVENT = "assistant.draft-updated";
    public static final String READY_EVENT = "assistant.ready";
    public static final String PLAYBOOKS_SYNCED_EVENT = "assistant.playbooks-synced";
    static final String WORKFLOW_KIND = "workflow-draft";
    static final String PROVIDER_KIND = "provider-draft";

    private final AssistantPort agent;
    private final SessionStore sessions;
    private final WorkflowQueries workflows;
    private final RunsCommands runs;
    private final EventBus events;
    private final PlatformProperties props;
    private final Clock clock;
    private final Environment environment;

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
                AssistantSession created = new AssistantSession(id, m, blankToNull(workflow), who.id(), AssistantSession.IDLE, null, List.of(), null, now, now);
                sessions.save(created);
                started(created, who);
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
                AssistantSession created = new AssistantSession(id, m, name, who.id(), AssistantSession.IDLE, seedDraft(yaml, problems), List.of(), null, now, now);
                sessions.save(created);
                started(created, who);
                return id;
            }));
        });
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
            publish(MESSAGE_EVENT, working, who, data(working, WORKFLOW_KIND).put("role", "user").put("text", Words.clip(message, 2000)));
            String current = working.currentYaml();
            AssistantRequest request = new AssistantRequest(working, message, current, who, context(session, current, who));
            AtomicBoolean ended = new AtomicBoolean();
            Flux<AssistantEvent> run = agent.needsPlatformChecks() ? checked(request, who, 0) : Flux.defer(() -> agent.run(request));
            Flux<AssistantEvent> stream = run
                    .onErrorResume(e -> {
                        log.warn("assistant {} failed on session {}: {}", agent.mode(), sessionId, e.toString());
                        return Flux.just(Events.message(clock.instant(), "The assistant failed: " + e.getMessage()), Events.failed(clock.instant(), e.getMessage()));
                    })
                    .takeUntil(Events::isDone)
                    .doOnNext(e -> record(sessionId, e, ended, who));
            return stream.concatWith(Flux.defer(() -> {
                if (ended.get()) {
                    return Flux.empty();
                }
                AssistantEvent done = Events.done(clock.instant());
                record(sessionId, done, ended, who);
                return Flux.just(done);
            })).doOnCancel(() -> sessions.update(sessionId, s -> AssistantSession.WORKING.equals(s.status()) ? s.withStatus(AssistantSession.DONE, clock.instant()) : s));
        });
    }

    // ---------------------------------------------------------------- the platform's own checks

    /** What the platform found on the latest draft of a run, and whether the agent called that draft ready. */
    private record Reviewed(DraftReview review, boolean agentReady) {
    }

    private int maxRounds() {
        return Math.max(0, props.getAssistant().getMaxRounds());
    }

    /**
     * The agent's run with the platform's checks on every draft it writes, in place of the agent's
     * own validation. When the agent calls a draft ready and the platform still finds errors, they go
     * back on the agent's session as a follow-up, for up to {@code max-rounds} rounds.
     */
    private Flux<AssistantEvent> checked(AssistantRequest request, Identity who, int round) {
        AtomicReference<String> external = new AtomicReference<>(request.session() == null ? null : request.session().externalId());
        AtomicReference<Reviewed> last = new AtomicReference<>();
        AtomicReference<AssistantEvent> end = new AtomicReference<>();
        boolean lastRound = round >= maxRounds();
        Flux<AssistantEvent> run = Flux.defer(() -> agent.run(request))
                .filter(e -> !AssistantEvent.VALIDATION.equals(e.type()))
                .concatMap(e -> {
                    if (e.data() != null && e.data().hasNonNull("externalId")) {
                        external.set(e.data().get("externalId").asText());
                    }
                    if (AssistantEvent.DRAFT.equals(e.type())) {
                        return reviewed(e, who, round, lastRound, last);
                    }
                    if (Events.isDone(e)) {
                        end.set(e);
                        return Flux.empty();
                    }
                    return Flux.just(e);
                });
        return run.concatWith(Flux.defer(() -> {
            AssistantEvent done = end.get();
            if (done == null) {
                return Flux.empty();
            }
            Reviewed r = last.get();
            if (Events.isFailed(done) || r == null || !r.agentReady() || r.review().clean()) {
                return Flux.just(done);
            }
            int errors = r.review().errors().size();
            if (lastRound || external.get() == null || request.session() == null) {
                return Flux.just(Events.message(clock.instant(), "The platform's checks still find " + count(errors, "error") + " after "
                        + count(round, "round") + " with the agent, so the draft needs a person: "
                        + String.join("; ", r.review().errors().stream().map(DraftReview::line).toList()) + "."), done);
            }
            AssistantSession session = request.session().withExternalId(external.get(), clock.instant());
            AssistantRequest next = new AssistantRequest(session, r.review().followUp(round + 1, maxRounds()), r.review().yaml(), who, request.context(), true);
            return Flux.concat(
                    Flux.just(Events.status(clock.instant(), "The platform's checks found " + count(errors, "error") + "; sending them back to the agent (round "
                            + (round + 1) + " of " + maxRounds() + ")")),
                    checked(next, who, round + 1));
        }));
    }

    /** The draft with the platform's validation and dry run, followed by the platform's problems. */
    private Flux<AssistantEvent> reviewed(AssistantEvent e, Identity who, int round, boolean lastRound, AtomicReference<Reviewed> last) {
        String yaml = e.data() == null ? "" : e.data().path("workflowYaml").asText("");
        if (yaml.isBlank()) {
            return Flux.just(e);
        }
        boolean agentReady = WorkflowDraft.READY.equals(e.data().path("status").asText());
        Reviewed previous = last.get();
        Mono<DraftReview> review = previous != null && previous.review().yaml().equals(yaml) ? Mono.just(previous.review()) : review(yaml, who);
        return review.flatMapMany(rv -> {
            last.set(new Reviewed(rv, agentReady));
            Instant at = clock.instant();
            return Flux.just(Events.draft(at, rv.applyTo(e.data(), round + 1, lastRound)), Events.validation(at, rv.findings()));
        });
    }

    /** The workflow checks against the live catalog, and a dry run on sample input when they pass. */
    Mono<DraftReview> review(String yaml, Identity who) {
        return workflows.check(yaml).defaultIfEmpty(List.of()).flatMap(problems -> {
            if (problems.stream().anyMatch(Problem::isError)) {
                return Mono.just(new DraftReview(yaml, problems, null));
            }
            WorkflowDefinition def;
            try {
                def = WorkflowYaml.parse(yaml);
            } catch (IllegalArgumentException ex) {
                return Mono.just(new DraftReview(yaml, List.of(Problem.error("yaml.invalid", null, null, ex.getMessage())), null));
            }
            JsonNode input = Schemas.sample(def.inputSchema());
            return Mono.defer(() -> runs.dryRun(def, input, who == null ? Identity.SYSTEM : who))
                    .map(dry -> new DraftReview(yaml, problems, dry))
                    .defaultIfEmpty(new DraftReview(yaml, problems, null))
                    .onErrorResume(ex -> {
                        log.info("dry run of an agent draft failed: {}", ex.toString());
                        return Mono.just(new DraftReview(yaml, problems, new DryRunResult(false, List.of(), List.of(), ex.getMessage())));
                    });
        });
    }

    // ---------------------------------------------------------------- the session record and its events

    /** Keep the session in step with the stream: the latest draft, the assistant's words, the agent's own id, the end. */
    private void record(String sessionId, AssistantEvent e, AtomicBoolean ended, Identity who) {
        Instant now = clock.instant();
        if (e.data() != null && e.data().hasNonNull("externalId")) {
            String external = e.data().get("externalId").asText();
            sessions.update(sessionId, s -> s.withExternalId(external, now));
        }
        switch (e.type()) {
            case AssistantEvent.DRAFT -> sessions.update(sessionId, s -> s.withDraft(e.data(), now))
                    .ifPresent(s -> publish(DRAFT_UPDATED_EVENT, s, who, draftData(s, WORKFLOW_KIND, e.data())));
            case AssistantEvent.MESSAGE -> {
                String t = Events.textOf(e);
                if (!t.isBlank()) {
                    sessions.update(sessionId, s -> s.withTurn("assistant", t, now))
                            .ifPresent(s -> publish(MESSAGE_EVENT, s, who, data(s, WORKFLOW_KIND).put("role", "assistant").put("text", Words.clip(t, 2000))));
                }
            }
            case AssistantEvent.DONE -> {
                ended.set(true);
                String status = Events.isFailed(e) ? AssistantSession.FAILED : AssistantSession.DONE;
                sessions.update(sessionId, s -> s.withStatus(status, now)).ifPresent(s -> {
                    if (!Events.isFailed(e) && s.draft() != null && WorkflowDraft.READY.equals(s.draft().path("status").asText())) {
                        publish(READY_EVENT, s, who, draftData(s, WORKFLOW_KIND, s.draft()));
                    }
                });
            }
            default -> {
            }
        }
    }

    private void started(AssistantSession s, Identity who) {
        publish(STARTED_EVENT, s, who, data(s, WORKFLOW_KIND).put("mode", s.mode()));
    }

    private void publish(String type, AssistantSession s, Identity who, ObjectNode data) {
        events.publish(type, AGGREGATE, s.id(), who == null ? Identity.SYSTEM.id() : who.id(), data);
    }

    /** What every assistant event carries: the draft (the builder session), the user, the kind, the agent, the agent's own session. */
    private ObjectNode data(AssistantSession s, String kind) {
        ObjectNode d = Events.mapper().createObjectNode();
        d.put("draftId", s.id());
        d.put("user", s.requester());
        d.put("kind", kind);
        d.put("agent", agent.mode());
        if (s.workflow() != null) {
            d.put("workflow", s.workflow());
        }
        if (s.externalId() != null) {
            d.put("externalId", s.externalId());
        }
        return d;
    }

    private ObjectNode draftData(AssistantSession s, String kind, JsonNode draft) {
        ObjectNode d = data(s, kind);
        d.put("status", draft.path("status").asText(""));
        String yaml = draft.path("workflowYaml").asText("");
        if (!yaml.isBlank()) {
            d.put("contentHash", WorkflowYaml.sha256(yaml));
        }
        if (draft.path("validation").isObject()) {
            d.put("valid", draft.path("validation").path("valid").asBoolean());
            d.put("errors", draft.path("validation").path("errors").size());
        }
        return d;
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
        return new Context(owner, folder, team, platformApi(), before.history() == null ? List.of() : before.history());
    }

    private String platformApi() {
        String configured = props.getAssistant().getPlatformApiUrl();
        if (configured != null && !configured.isBlank()) {
            return configured.trim().replaceAll("/+$", "");
        }
        String port = environment == null ? "8080" : environment.getProperty("server.port", "8080");
        return "http://localhost:" + port;
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
        return CurrentIdentity.current().flatMap(who -> {
            String draftId = Ids.next("pdraft");
            String team = who.teams() == null || who.teams().isEmpty() ? null : who.teams().get(0);
            ObjectNode base = Events.mapper().createObjectNode().put("draftId", draftId).put("user", who.id()).put("kind", PROVIDER_KIND).put("agent", agent.mode());
            events.publish(STARTED_EVENT, AGGREGATE, draftId, who.id(), base.deepCopy().put("description", Words.clip(description.trim(), 2000)));
            ProviderRequest request = new ProviderRequest(draftId, description.trim(), who, new Context(team, null, team, platformApi(), List.of()));
            return agent.draftProvider(request).doOnNext(d -> {
                ObjectNode data = base.deepCopy();
                if (d.yaml() != null && !d.yaml().isBlank()) {
                    data.put("contentHash", WorkflowYaml.sha256(d.yaml()));
                }
                data.put("questions", d.questions() == null ? 0 : d.questions().size());
                events.publish(DRAFT_UPDATED_EVENT, AGGREGATE, draftId, who.id(), data);
                if (d.yaml() != null && !d.yaml().isBlank() && (d.questions() == null || d.questions().isEmpty())) {
                    events.publish(READY_EVENT, AGGREGATE, draftId, who.id(), data.deepCopy());
                }
            });
        });
    }

    @Override
    public Mono<List<PlaybookSync>> syncPlaybooks() {
        return CurrentIdentity.current().flatMap(who -> {
            if (!who.admin()) {
                throw new ForbiddenException(who.name() + " may not sync the assistant playbooks (admins only)");
            }
            return agent.syncPlaybooks().doOnNext(result -> {
                ObjectNode data = Events.mapper().createObjectNode().put("agent", agent.mode());
                ArrayNode list = data.putArray("playbooks");
                result.forEach(p -> list.addObject().put("file", p.file()).put("macro", p.macro()).put("playbookId", p.playbookId()).put("action", p.action()));
                events.publish(PLAYBOOKS_SYNCED_EVENT, "assistant", agent.mode(), who.id(), data);
            });
        });
    }

    private static String count(int n, String what) {
        return n + " " + what + (n == 1 ? "" : "s");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
