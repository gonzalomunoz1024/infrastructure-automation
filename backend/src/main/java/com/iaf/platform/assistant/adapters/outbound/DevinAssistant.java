package com.iaf.platform.assistant.adapters.outbound;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.assistant.domain.AssistantEvent;
import com.iaf.platform.assistant.domain.AssistantSession;
import com.iaf.platform.assistant.domain.Events;
import com.iaf.platform.assistant.domain.ProviderDraft;
import com.iaf.platform.assistant.domain.WorkflowDraft;
import com.iaf.platform.assistant.domain.Words;
import com.iaf.platform.assistant.ports.outbound.AssistantPort;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.domain.Problem;
import com.iaf.platform.config.PlatformProperties;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The production agent: Devin, through its API v3. A builder message becomes a Devin session
 * running the {@code !workflow-draft} playbook (or a message on the existing session), polled
 * every few seconds; status changes, Devin's messages and every change of the structured output
 * become events. Timeouts and errors end the stream with a message and a failed done.
 */
@Slf4j
@Component
@Profile("devin")
@Primary
public class DevinAssistant implements AssistantPort {

    static final String WORKFLOW_MACRO = "!workflow-draft";
    static final String PROVIDER_MACRO = "!provider-draft";
    private static final Set<String> TERMINAL = Set.of("blocked", "finished", "expired", "stopped", "suspended");

    private final PlatformProperties.Assistant props;
    private final WebClient client;
    private final Clock clock;
    private final Duration pollEvery;
    private final Duration timeout;

    @Autowired
    public DevinAssistant(PlatformProperties props, WebClient.Builder builder, Clock clock) {
        this(props.getAssistant(), builder.clone().baseUrl(props.getAssistant().getDevinUrl()).build(), clock, Duration.ofSeconds(3), Duration.ofMinutes(30));
    }

    public DevinAssistant(PlatformProperties.Assistant props, WebClient client, Clock clock, Duration pollEvery, Duration timeout) {
        this.props = props;
        this.client = client;
        this.clock = clock;
        this.pollEvery = pollEvery;
        this.timeout = timeout;
    }

    @Override
    public String mode() {
        return "devin";
    }

    /** A moment of a Devin session, as polled. */
    record Snapshot(String status, List<JsonNode> messages, JsonNode structuredOutput) {
        static final Snapshot NONE = new Snapshot(null, List.of(), null);
    }

    @Override
    public Flux<AssistantEvent> run(AssistantRequest request) {
        return Flux.defer(() -> {
            String external = request.session() == null ? null : request.session().externalId();
            boolean fresh = external == null || external.isBlank();
            Mono<String> sessionId = fresh
                    ? createSession(prompt(request), props.getDevinPlaybookId(), "workflow-draft", "Workflow draft: " + Words.clip(request.text(), 60))
                    : sendMessage(external, followUp(request)).thenReturn(external);
            AtomicBoolean ended = new AtomicBoolean();
            return sessionId.flatMapMany(id -> Flux.concat(
                    Flux.just(Events.status(now(), fresh ? "Devin session " + id + " started" : "Sent to Devin session " + id, id)),
                    poll(id, !fresh).doOnNext(e -> {
                        if (Events.isDone(e)) {
                            ended.set(true);
                        }
                    })))
                    .take(timeout)
                    .concatWith(Flux.defer(() -> ended.get() ? Flux.empty()
                            : Flux.just(Events.message(now(), "Devin did not finish within " + timeout.toMinutes() + " minutes."),
                                    Events.failed(now(), "timeout after " + timeout))));
        }).onErrorResume(e -> {
            log.warn("devin assistant failed: {}", e.toString());
            return Flux.just(Events.message(now(), "Devin could not be reached: " + e.getMessage()), Events.failed(now(), e.getMessage()));
        });
    }

    @Override
    public Mono<ProviderDraft> draftProvider(String description, Identity who) {
        String team = who == null || who.teams() == null || who.teams().isEmpty() ? "" : who.teams().get(0);
        String prompt = PROVIDER_MACRO + "\n\nDESCRIPTION:\n" + description + "\n\nCONTEXT:\nowner: " + team + "\nrequester_team: " + team + "\n";
        String playbook = props.getDevinProviderPlaybookId() == null || props.getDevinProviderPlaybookId().isBlank() ? props.getDevinPlaybookId() : props.getDevinProviderPlaybookId();
        return createSession(prompt, playbook, "provider-draft", "Provider draft: " + Words.clip(description, 60))
                .flatMapMany(this::snapshots)
                .take(timeout)
                .takeUntil(s -> terminal(s, null))
                .filter(s -> s.structuredOutput() != null && s.structuredOutput().isObject())
                .reduce((first, second) -> second)
                .map(s -> providerDraft(s.structuredOutput()))
                .switchIfEmpty(Mono.error(new IllegalStateException("Devin returned no provider draft")));
    }

    // ---------------------------------------------------------------- the Devin API

    Mono<String> createSession(String prompt, String playbookId, String tag, String title) {
        ObjectNode body = Events.mapper().createObjectNode();
        body.put("prompt", prompt);
        if (playbookId != null && !playbookId.isBlank()) {
            body.put("playbook_id", playbookId);
        }
        if (props.getPlatformTokenSecretId() != null && !props.getPlatformTokenSecretId().isBlank()) {
            body.putArray("secret_ids").add(props.getPlatformTokenSecretId());
        }
        body.putArray("tags").add(tag);
        body.put("title", title);
        return client.post().uri("/sessions")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(n -> {
                    String id = n.path("session_id").asText(n.path("id").asText(null));
                    if (id == null || id.isBlank()) {
                        throw new IllegalStateException("Devin returned no session id");
                    }
                    return id;
                });
    }

    Mono<Void> sendMessage(String sessionId, String message) {
        ObjectNode body = Events.mapper().createObjectNode();
        body.put("message", message);
        return client.post().uri("/sessions/{id}/message", sessionId)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .then();
    }

    Mono<Snapshot> getSession(String sessionId) {
        return client.get().uri("/sessions/{id}", sessionId)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(DevinAssistant::snapshot);
    }

    static Snapshot snapshot(JsonNode n) {
        String status = n.path("status_enum").asText(n.path("status").asText(null));
        List<JsonNode> messages = new ArrayList<>();
        if (n.path("messages").isArray()) {
            n.get("messages").forEach(messages::add);
        }
        JsonNode so = n.get("structured_output");
        return new Snapshot(status, messages, so == null || so.isNull() ? null : so);
    }

    private String bearer() {
        return "Bearer " + (props.getDevinApiKey() == null ? "" : props.getDevinApiKey());
    }

    /** The session polled every {@code pollEvery} until the stream is cancelled. */
    private Flux<Snapshot> snapshots(String sessionId) {
        return getSession(sessionId).repeatWhen(completions -> completions.delayElements(pollEvery));
    }

    Flux<AssistantEvent> poll(String sessionId) {
        return poll(sessionId, false);
    }

    /**
     * Status changes, new Devin messages and structured-output changes as events, ending with done.
     * After a follow-up message the first snapshot may still show the session blocked from before
     * the message was read, so {@code followUp} ignores a blocked status until Devin has moved.
     */
    Flux<AssistantEvent> poll(String sessionId, boolean followUp) {
        return Flux.defer(() -> {
            AtomicReference<Snapshot> previous = new AtomicReference<>(Snapshot.NONE);
            AtomicBoolean moved = new AtomicBoolean(!followUp);
            // the structured output as it stood before this message: a final status there is last turn's, not this one's
            AtomicReference<JsonNode> baseline = new AtomicReference<>();
            return snapshots(sessionId)
                    .concatMap(snap -> {
                        Snapshot before = previous.getAndSet(snap);
                        if (followUp && before == Snapshot.NONE) {
                            baseline.set(snap.structuredOutput());
                        }
                        boolean settle = moved.get();
                        if (!settle && snap.status() != null && !"blocked".equals(snap.status())) {
                            moved.set(true);
                            settle = true;
                        }
                        if (!settle && snap.structuredOutput() != null && !snap.structuredOutput().equals(before.structuredOutput()) && before != Snapshot.NONE) {
                            moved.set(true);
                            settle = true;
                        }
                        return Flux.fromIterable(diff(before, snap, settle, baseline.get()));
                    })
                    .takeUntil(Events::isDone);
        });
    }

    List<AssistantEvent> diff(Snapshot before, Snapshot after) {
        return diff(before, after, true, null);
    }

    List<AssistantEvent> diff(Snapshot before, Snapshot after, boolean mayEnd, JsonNode baseline) {
        List<AssistantEvent> out = new ArrayList<>();
        Instant at = now();
        if (after.status() != null && !after.status().equals(before.status())) {
            out.add(Events.status(at, "Devin is " + after.status().replace('_', ' ')));
        }
        for (int i = before.messages().size(); i < after.messages().size(); i++) {
            JsonNode m = after.messages().get(i);
            String type = m.path("type").asText("devin_message");
            if (type.startsWith("devin") || type.equals("assistant")) {
                String text = m.path("message").asText(m.path("text").asText(""));
                if (!text.isBlank()) {
                    out.add(Events.message(at, text));
                }
            }
        }
        JsonNode so = after.structuredOutput();
        if (so != null && so.isObject() && !so.equals(before.structuredOutput())) {
            out.add(Events.draft(at, so));
            if (so.path("validation").isObject()) {
                List<Problem> problems = new ArrayList<>();
                for (JsonNode err : so.path("validation").path("errors")) {
                    problems.add(Problem.error("devin", null, null, err.asText()));
                }
                if (!so.path("validation").path("valid").asBoolean(true) && problems.isEmpty()) {
                    problems.add(Problem.error("devin", null, null, "Devin reports the draft as invalid"));
                }
                out.add(Events.validation(at, problems));
            }
        }
        if (mayEnd && terminal(after, baseline)) {
            if ("expired".equals(after.status())) {
                out.add(Events.message(at, "The Devin session expired before the draft was finished."));
                out.add(Events.failed(at, "session expired"));
            } else {
                out.add(Events.done(at));
            }
        }
        return out;
    }

    /**
     * Done when Devin stops (blocked waits for the user, finished, expired) or the structured output
     * reached a final status; {@code baseline} is the output from before a follow-up, which does not count.
     */
    static boolean terminal(Snapshot s, JsonNode baseline) {
        if (s.status() != null && TERMINAL.contains(s.status().toLowerCase())) {
            return true;
        }
        JsonNode so = s.structuredOutput();
        if (so == null || !so.isObject() || so.equals(baseline)) {
            return false;
        }
        String status = so.path("status").asText("");
        return WorkflowDraft.READY.equals(status) || WorkflowDraft.NEEDS_INPUT.equals(status) || WorkflowDraft.FAILED.equals(status) || "done".equals(status);
    }

    static ProviderDraft providerDraft(JsonNode so) {
        List<String> assumptions = new ArrayList<>();
        so.path("assumptions").forEach(a -> assumptions.add(a.asText()));
        List<String> questions = new ArrayList<>();
        so.path("questions").forEach(q -> questions.add(q.isObject() ? q.path("question").asText() : q.asText()));
        return new ProviderDraft(so.path("providerYaml").asText(""), so.path("summary").asText(""), assumptions, questions);
    }

    // ---------------------------------------------------------------- prompts

    /** The macro line and the DESCRIPTION, MODE, CURRENT_DRAFT and CONTEXT blocks the playbook reads. */
    static String prompt(AssistantRequest request) {
        return WORKFLOW_MACRO + "\n\n" + blocks(request);
    }

    /** A follow-up on an existing session: the same blocks without the macro, since the playbook is already running. */
    static String followUp(AssistantRequest request) {
        return blocks(request);
    }

    static String blocks(AssistantRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("DESCRIPTION:\n").append(request.text() == null ? "" : request.text().trim()).append("\n\n");
        boolean edit = (request.session() != null && request.session().isEdit()) || (request.currentDraft() != null && !request.currentDraft().isBlank());
        sb.append("MODE: ").append(edit ? AssistantSession.EDIT : AssistantSession.CREATE).append("\n\n");
        sb.append("CURRENT_DRAFT:\n");
        if (request.currentDraft() == null || request.currentDraft().isBlank()) {
            sb.append("none\n\n");
        } else {
            sb.append("```yaml\n").append(request.currentDraft().trim()).append("\n```\n\n");
        }
        Context ctx = request.context();
        sb.append("CONTEXT:\n");
        sb.append("owner: ").append(ctx == null || ctx.owner() == null ? "" : ctx.owner()).append('\n');
        sb.append("folder: ").append(ctx == null || ctx.folder() == null ? "" : ctx.folder()).append('\n');
        sb.append("requester_team: ").append(ctx == null || ctx.requesterTeam() == null ? "" : ctx.requesterTeam()).append('\n');
        sb.append("requester: ").append(request.who() == null ? "" : request.who().id()).append('\n');
        sb.append("PLATFORM_API: ").append(ctx == null || ctx.platformApi() == null ? "" : ctx.platformApi()).append('\n');
        return sb.toString();
    }

    private Instant now() {
        return clock.instant();
    }

    static ArrayNode array() {
        return Events.mapper().createArrayNode();
    }
}
