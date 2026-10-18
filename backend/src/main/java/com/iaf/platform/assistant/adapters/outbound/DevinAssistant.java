package com.iaf.platform.assistant.adapters.outbound;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.assistant.adapters.outbound.PlaybookFiles.PlaybookFile;
import com.iaf.platform.assistant.domain.AssistantEvent;
import com.iaf.platform.assistant.domain.AssistantSession;
import com.iaf.platform.assistant.domain.DraftExplainer;
import com.iaf.platform.assistant.domain.Events;
import com.iaf.platform.assistant.domain.ProviderDraft;
import com.iaf.platform.assistant.domain.WorkflowDraft;
import com.iaf.platform.assistant.domain.Words;
import com.iaf.platform.assistant.ports.outbound.AssistantPort;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.domain.Ids;
import com.iaf.platform.config.PlatformProperties;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;

/**
 * The production agent: Devin, through its API v3 at organization scope
 * ({@code <devin-url>/organizations/<devin-org>}). A builder message becomes a session
 * ({@code POST /sessions}) running the {@code !workflow-draft} playbook with the draft schema as its
 * required structured output, the workflows repo as read-only context, tags naming the draft, the
 * user and the kind, and an ACU ceiling; later messages go to the same session
 * ({@code POST /sessions/{id}/messages}). The session ({@code GET /sessions/{id}}) and its messages
 * ({@code GET /sessions/{id}/messages}) are polled every {@code devin-poll-every} (15 s, kept within
 * 10 to 30 s): status changes, Devin's messages and every change of the structured output become
 * events. Messages are read once: each poll reads on from the {@code end_cursor} of the last page,
 * and only a cursor Devin refuses sends the read back to the start. The platform checks every
 * draft itself, so Devin's own validation is not passed on. Timeouts and errors end the stream with
 * a message and a failed done. A session nobody waits for any more (the builder closed the stream
 * before the draft was done, or the timeout fired) is ended and archived
 * ({@code DELETE /sessions/{id}?archive=true}), best effort, so it stops spending ACUs. Admins
 * create or update the playbooks from {@code devin/*.md} ({@code GET}, {@code POST} and
 * {@code PUT /playbooks}).
 */
@Slf4j
@Component
@Profile("devin")
@Primary
public class DevinAssistant implements AssistantPort {

    static final String WORKFLOW_MACRO = "!workflow-draft";
    static final String PROVIDER_MACRO = "!provider-draft";
    static final String WORKFLOW_KIND = "workflow-draft";
    static final String PROVIDER_KIND = "provider-draft";
    static final String WORKFLOW_SCHEMA = "workflow-draft.schema.json";
    static final String PROVIDER_SCHEMA = "provider-draft.schema.json";
    /** The prompt's MODE when the user asks what the draft does (the playbook's explain mode). */
    static final String EXPLAIN_MODE = "explain";
    static final Duration MIN_POLL = Duration.ofSeconds(10);
    static final Duration MAX_POLL = Duration.ofSeconds(30);
    static final Duration DEFAULT_POLL = Duration.ofSeconds(15);
    private static final int PAGE_SIZE = 200;
    private static final int MAX_PAGES = 20;
    /** How long ending a session may take; it never holds up the stream. */
    static final Duration END_TIMEOUT = Duration.ofSeconds(10);
    /** Session statuses on the way to work: Devin has picked the message up. */
    private static final Set<String> STARTING = Set.of("new", "claimed", "resuming");
    /** Suspension reasons that end a session for good: limits, billing, errors. */
    private static final Set<String> STOPPED = Set.of("usage_limit_exceeded", "out_of_credits", "out_of_quota", "no_quota_allocation",
            "payment_declined", "org_usage_limit_exceeded", "user_usage_limit_exceeded", "total_session_limit_exceeded", "contract_expired", "error");

    private final PlatformProperties.Assistant props;
    private final Path playbookDir;
    private final WebClient client;
    private final Clock clock;
    private final Duration pollEvery;
    private final Duration timeout;
    /** Playbook ids by macro from the last sync, used when none is configured. */
    private final Map<String, String> synced = new ConcurrentHashMap<>();

    @Autowired
    public DevinAssistant(PlatformProperties props, WebClient.Builder builder, Clock clock) {
        this(props.getAssistant(), playbookDir(props), builder.clone().baseUrl(baseUrl(props.getAssistant())).build(), clock,
                pollEvery(props.getAssistant().getDevinPollEvery()), props.getAssistant().getDevinTimeout());
    }

    /** {@code client} already points at the organization ({@link #baseUrl}); tests poll faster than the 10 s floor. */
    public DevinAssistant(PlatformProperties.Assistant props, Path playbookDir, WebClient client, Clock clock, Duration pollEvery, Duration timeout) {
        this.props = props;
        this.playbookDir = playbookDir;
        this.client = client;
        this.clock = clock;
        this.pollEvery = pollEvery;
        this.timeout = timeout == null ? Duration.ofMinutes(30) : timeout;
    }

    static String baseUrl(PlatformProperties.Assistant p) {
        String root = p.getDevinUrl() == null ? "" : p.getDevinUrl().trim().replaceAll("/+$", "");
        return root + "/organizations/" + (p.getDevinOrg() == null ? "" : p.getDevinOrg().trim());
    }

    static Path playbookDir(PlatformProperties props) {
        String dir = props.getAssistant().getDevinDir();
        return dir == null || dir.isBlank() ? Path.of(props.getContent().getDir(), "devin") : Path.of(dir);
    }

    /** The configured poll interval, kept within 10 to 30 seconds. */
    static Duration pollEvery(Duration configured) {
        if (configured == null) {
            return DEFAULT_POLL;
        }
        if (configured.compareTo(MIN_POLL) < 0) {
            log.warn("devin-poll-every {} is under {}; polling every {}", configured, MIN_POLL, MIN_POLL);
            return MIN_POLL;
        }
        if (configured.compareTo(MAX_POLL) > 0) {
            log.warn("devin-poll-every {} is over {}; polling every {}", configured, MAX_POLL, MAX_POLL);
            return MAX_POLL;
        }
        return configured;
    }

    @Override
    public String mode() {
        return "devin";
    }

    /** A moment of a Devin session, as polled: the v3 status and status_detail, the messages so far, the structured output. */
    record Snapshot(String status, String detail, List<Message> messages, JsonNode structuredOutput) {
        static final Snapshot NONE = new Snapshot(null, null, List.of(), null);

        Set<String> messageIds() {
            Set<String> ids = new HashSet<>();
            messages.forEach(m -> ids.add(m.id()));
            return ids;
        }
    }

    /** One message of a session; {@code source} is devin or user. */
    record Message(String id, String source, String text) {
    }

    private record Started(String id, Snapshot before) {
    }

    /** One page of messages: {@code next} is the cursor of the next page, {@code end} the cursor after this one's last item. */
    private record Page(List<Message> items, String next, String end) {
    }

    /**
     * The messages of one session read so far, and the cursor to read on from: the
     * {@code end_cursor} of the last page read. Polls run one after another, on whatever thread.
     */
    static final class Transcript {
        private final List<Message> messages = new ArrayList<>();
        private final Set<String> ids = new HashSet<>();
        private String cursor;

        synchronized String cursor() {
            return cursor;
        }

        /** Forgets everything, so the next read starts from the first message. */
        synchronized void restart() {
            messages.clear();
            ids.clear();
            cursor = null;
        }

        /** Adds the messages not seen yet, moves the cursor on, and answers every message so far. */
        synchronized List<Message> add(List<Page> pages) {
            for (Page p : pages) {
                for (Message m : p.items()) {
                    if (ids.add(m.id())) {
                        messages.add(m);
                    }
                }
                if (p.end() != null) {
                    cursor = p.end();
                }
            }
            return List.copyOf(messages);
        }
    }

    // ---------------------------------------------------------------- the port

    @Override
    public Flux<AssistantEvent> run(AssistantRequest request) {
        return Flux.defer(() -> {
            requireOrg();
            String external = request.session() == null ? null : request.session().externalId();
            boolean fresh = external == null || external.isBlank();
            Transcript transcript = new Transcript();
            Mono<Started> started = fresh
                    ? workflowSession(request).flatMap(this::createSession).map(id -> new Started(id, Snapshot.NONE))
                    // what the session showed before this message, so neither its old messages nor its old output count as new
                    : snapshot(external, transcript).flatMap(before -> sendMessage(external, request.fromPlatform() ? request.text() : followUp(request))
                            .thenReturn(new Started(external, before)));
            AtomicBoolean ended = new AtomicBoolean();
            AtomicReference<String> session = new AtomicReference<>();
            return started.flatMapMany(s -> {
                        session.set(s.id());
                        return Flux.concat(
                                Flux.just(Events.status(now(), opening(fresh, request.fromPlatform(), s.id()), s.id())),
                                poll(s.id(), s.before(), !fresh, transcript).doOnNext(e -> {
                                    if (Events.isDone(e)) {
                                        ended.set(true);
                                    }
                                }));
                    })
                    .take(timeout)
                    // the builder stopped listening, or the timeout fired, before Devin finished: nobody will read the
                    // rest, so the session is ended; an error leaves it alone (the next message may still reach it)
                    .doFinally(signal -> {
                        if (!ended.get() && signal != SignalType.ON_ERROR && session.get() != null) {
                            end(session.get(), signal == SignalType.CANCEL ? "the builder stopped listening" : "no draft within " + minutes(timeout));
                        }
                    })
                    .concatWith(Flux.defer(() -> ended.get() ? Flux.empty()
                            : Flux.just(Events.message(now(), "Devin did not finish within " + minutes(timeout)
                                            + ", so the platform is ending the session; your next message starts a new one."),
                                    Events.sessionEnded(now(), "Ending Devin session " + session.get()),
                                    Events.failed(now(), "timeout after " + timeout))));
        }).onErrorResume(e -> {
            log.warn("devin assistant failed: {}", e.toString());
            return Flux.just(Events.message(now(), "Devin could not be reached: " + e.getMessage()), Events.failed(now(), e.getMessage()));
        });
    }

    private static String opening(boolean fresh, boolean fromPlatform, String id) {
        if (fresh) {
            return "Devin session " + id + " started";
        }
        return (fromPlatform ? "Sent the platform's checks to Devin session " : "Sent to Devin session ") + id;
    }

    @Override
    public Mono<ProviderDraft> draftProvider(String description, Identity who) {
        return draftProvider(new ProviderRequest(Ids.next("pdraft"), description, who, null));
    }

    @Override
    public Mono<ProviderDraft> draftProvider(ProviderRequest request) {
        return Mono.defer(() -> {
            requireOrg();
            String playbook = playbookFor(PROVIDER_MACRO, props.getDevinProviderPlaybookId());
            return sessionBody(providerPrompt(request), playbook, PROVIDER_SCHEMA, tags(PROVIDER_KIND, request.draftId(), request.who()),
                    "Provider draft: " + Words.clip(request.description(), 60))
                    .flatMap(this::createSession)
                    .flatMap(id -> {
                        AtomicBoolean finished = new AtomicBoolean();
                        return snapshots(id, null)
                                .take(timeout)
                                .takeUntil(s -> {
                                    boolean last = !ending(s, true, null, now()).isEmpty();
                                    if (last) {
                                        finished.set(true);
                                    }
                                    return last;
                                })
                                // the wizard left or the timeout fired before Devin finished: end the session
                                .doFinally(signal -> {
                                    if (!finished.get() && signal != SignalType.ON_ERROR) {
                                        end(id, signal == SignalType.CANCEL ? "the provider wizard stopped listening" : "no provider draft within " + minutes(timeout));
                                    }
                                })
                                .filter(s -> s.structuredOutput() != null && s.structuredOutput().isObject())
                                .reduce((first, second) -> second);
                    })
                    .map(s -> providerDraft(s.structuredOutput()))
                    .switchIfEmpty(Mono.error(new IllegalStateException("Devin returned no provider draft")));
        });
    }

    /** Creates the playbooks Devin does not have (by macro) and updates the ones whose title, body or schema changed. */
    @Override
    public Mono<List<PlaybookSync>> syncPlaybooks() {
        return Mono.defer(() -> {
            requireOrg();
            return Mono.fromCallable(() -> PlaybookFiles.read(playbookDir)).subscribeOn(Schedulers.boundedElastic())
                    .flatMap(files -> existingPlaybooks().flatMap(existing -> Flux.fromIterable(files).concatMap(f -> sync(f, existing)).collectList()));
        });
    }

    @EventListener(ApplicationReadyEvent.class)
    public void syncAtStartup() {
        if (!props.isSyncPlaybooksOnStartup()) {
            return;
        }
        syncPlaybooks().subscribe(
                r -> log.info("devin playbooks synced: {}", r.stream().map(s -> s.macro() + " " + s.action()).toList()),
                e -> log.warn("devin playbook sync at startup failed: {}", e.toString()));
    }

    // ---------------------------------------------------------------- sessions

    /** The {@code POST /sessions} body of a workflow draft; the draft id is the builder session's. */
    Mono<ObjectNode> workflowSession(AssistantRequest r) {
        String draftId = r.session() == null ? Ids.next("asst") : r.session().id();
        return sessionBody(prompt(r), playbookFor(WORKFLOW_MACRO, props.getDevinPlaybookId()), WORKFLOW_SCHEMA,
                tags(WORKFLOW_KIND, draftId, r.who()), "Workflow draft: " + Words.clip(r.text(), 60));
    }

    Mono<ObjectNode> sessionBody(String prompt, String playbookId, String schemaFile, List<String> tags, String title) {
        return Mono.fromCallable(() -> {
            ObjectNode body = Events.mapper().createObjectNode();
            body.put("prompt", prompt);
            body.put("title", title);
            ArrayNode t = body.putArray("tags");
            tags.forEach(t::add);
            List<String> repos = props.getDevinRepos() == null ? List.of() : props.getDevinRepos().stream().filter(s -> s != null && !s.isBlank()).toList();
            if (!repos.isEmpty()) {
                ArrayNode r = body.putArray("repos");
                repos.forEach(r::add);
            }
            if (playbookId != null && !playbookId.isBlank()) {
                body.put("playbook_id", playbookId);
            }
            if (props.getPlatformTokenSecretId() != null && !props.getPlatformTokenSecretId().isBlank()) {
                body.putArray("secret_ids").add(props.getPlatformTokenSecretId());
            }
            if (props.getDevinMaxAcuLimit() > 0) {
                body.put("max_acu_limit", props.getDevinMaxAcuLimit());
            }
            JsonNode schema = PlaybookFiles.schemaOrNull(playbookDir, schemaFile);
            if (schema == null) {
                log.warn("no {} in {}; the session runs without a structured-output schema", schemaFile, playbookDir);
            } else {
                body.set("structured_output_schema", schema);
            }
            body.put("structured_output_required", true);
            return body;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** {@code draft:<id>}, {@code user:<id>} and {@code kind:<workflow-draft|provider-draft>}, so a session leads back to the platform. */
    static List<String> tags(String kind, String draftId, Identity who) {
        List<String> tags = new ArrayList<>();
        tags.add("kind:" + kind);
        if (draftId != null) {
            tags.add("draft:" + draftId);
        }
        if (who != null && who.id() != null) {
            tags.add("user:" + who.id());
        }
        return tags;
    }

    Mono<String> createSession(ObjectNode body) {
        return client.post().uri("/sessions")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(n -> {
                    String id = n.path("session_id").asText("");
                    if (id.isBlank()) {
                        throw new IllegalStateException("Devin returned no session id");
                    }
                    return id;
                });
    }

    Mono<Void> sendMessage(String sessionId, String message) {
        ObjectNode body = Events.mapper().createObjectNode();
        body.put("message", message);
        return client.post().uri("/sessions/{id}/messages", sessionId)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .then();
    }

    /**
     * Ends a session nobody waits for any more and archives it ({@code DELETE /sessions/{id}?archive=true}),
     * in the background and best effort: a failure is logged, never passed on.
     */
    void end(String sessionId, String why) {
        client.delete().uri(b -> b.path("/sessions/{id}").queryParam("archive", "true").build(sessionId))
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .retrieve()
                .toBodilessEntity()
                .timeout(END_TIMEOUT)
                .subscribe(
                        ok -> log.info("ended Devin session {}: {}", sessionId, why),
                        e -> log.warn("could not end Devin session {} ({}): {}", sessionId, why, e.toString()));
    }

    /** {@code GET /sessions/{id}}, and its new messages when there is a {@code transcript} to keep them in. */
    Mono<Snapshot> snapshot(String sessionId, Transcript transcript) {
        Mono<JsonNode> session = client.get().uri("/sessions/{id}", sessionId)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .retrieve()
                .bodyToMono(JsonNode.class);
        Mono<List<Message>> messages = transcript == null ? Mono.just(List.of()) : messages(sessionId, transcript);
        return Mono.zip(session, messages).map(t -> {
            JsonNode n = t.getT1();
            JsonNode so = n.get("structured_output");
            return new Snapshot(n.path("status").asText(null), n.path("status_detail").asText(null), t.getT2(), so == null || so.isNull() ? null : so);
        });
    }

    /**
     * Every message of the session so far: the pages after the transcript's cursor ({@code first},
     * {@code after} and {@code end_cursor}) added to what it holds. A cursor Devin refuses (a 4xx)
     * starts the read again from the first message.
     */
    Mono<List<Message>> messages(String sessionId, Transcript transcript) {
        // deferred: each poll resubscribes and must read the cursor as the last poll left it
        return Mono.defer(() -> {
            String from = transcript.cursor();
            return pages(sessionId, from)
                    .onErrorResume(e -> from != null && e instanceof WebClientResponseException w && w.getStatusCode().is4xxClientError(), e -> {
                        log.info("Devin refused the message cursor of session {} ({}); reading its messages from the start", sessionId, e.getMessage());
                        transcript.restart();
                        return pages(sessionId, null);
                    })
                    .map(transcript::add);
        });
    }

    private Mono<List<Page>> pages(String sessionId, String after) {
        return page(sessionId, after)
                .expand(p -> p.next() == null ? Mono.empty() : page(sessionId, p.next()))
                .take(MAX_PAGES)
                .collectList();
    }

    private Mono<Page> page(String sessionId, String after) {
        return client.get().uri(b -> after == null
                ? b.path("/sessions/{id}/messages").queryParam("first", PAGE_SIZE).build(sessionId)
                : b.path("/sessions/{id}/messages").queryParam("first", PAGE_SIZE).queryParam("after", "{after}").build(sessionId, after))
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(n -> {
                    List<Message> items = new ArrayList<>();
                    int i = 0;
                    for (JsonNode m : n.path("items")) {
                        String id = m.path("event_id").asText("");
                        items.add(new Message(id.isBlank() ? (after == null ? "" : after) + "#" + i : id, m.path("source").asText(""), m.path("message").asText("")));
                        i++;
                    }
                    String cursor = n.path("end_cursor").asText("");
                    return new Page(items, n.path("has_next_page").asBoolean(false) && !cursor.isBlank() ? cursor : null, cursor.isBlank() ? null : cursor);
                });
    }

    private String bearer() {
        return "Bearer " + (props.getDevinApiKey() == null ? "" : props.getDevinApiKey());
    }

    private void requireOrg() {
        if (props.getDevinOrg() == null || props.getDevinOrg().isBlank()) {
            throw new IllegalStateException("platform.assistant.devin-org is not set");
        }
    }

    /** The session polled every {@code pollEvery} until the stream is cancelled; its messages read on in {@code transcript}. */
    private Flux<Snapshot> snapshots(String sessionId, Transcript transcript) {
        return snapshot(sessionId, transcript).repeatWhen(completions -> completions.delayElements(pollEvery));
    }

    /**
     * Status changes, new Devin messages and structured-output changes as events, ending with done.
     * {@code before} is the session as it stood before this message. After a follow-up the first
     * polls may still show the session waiting from before, so an end counts only once Devin has
     * moved: it started working, wrote a message or changed the output.
     */
    Flux<AssistantEvent> poll(String sessionId, Snapshot before, boolean followUp, Transcript transcript) {
        return Flux.defer(() -> {
            AtomicReference<Snapshot> previous = new AtomicReference<>(before);
            AtomicBoolean moved = new AtomicBoolean(!followUp);
            JsonNode baseline = followUp ? before.structuredOutput() : null;
            return snapshots(sessionId, transcript)
                    .concatMap(snap -> {
                        Snapshot last = previous.getAndSet(snap);
                        if (!moved.get() && moved(before, snap)) {
                            moved.set(true);
                        }
                        return Flux.fromIterable(diff(last, snap, moved.get(), baseline));
                    })
                    .takeUntil(Events::isDone);
        });
    }

    static boolean moved(Snapshot before, Snapshot now) {
        if ("working".equals(now.detail()) || (now.status() != null && STARTING.contains(now.status()))) {
            return true;
        }
        Set<String> seen = before.messageIds();
        if (now.messages().stream().anyMatch(m -> "devin".equals(m.source()) && !seen.contains(m.id()))) {
            return true;
        }
        return now.structuredOutput() != null && !now.structuredOutput().equals(before.structuredOutput());
    }

    List<AssistantEvent> diff(Snapshot before, Snapshot after, boolean mayEnd, JsonNode baseline) {
        List<AssistantEvent> out = new ArrayList<>();
        Instant at = now();
        if (after.status() != null && (!after.status().equals(before.status()) || !Objects.equals(after.detail(), before.detail()))) {
            out.add(Events.status(at, describe(after.status(), after.detail())));
        }
        Set<String> seen = before.messageIds();
        for (Message m : after.messages()) {
            if ("devin".equals(m.source()) && !seen.contains(m.id()) && !m.text().isBlank()) {
                out.add(Events.message(at, m.text()));
            }
        }
        JsonNode so = after.structuredOutput();
        if (so != null && so.isObject() && !so.equals(before.structuredOutput())) {
            out.add(Events.draft(at, so));
        }
        out.addAll(ending(after, mayEnd, baseline, at));
        return out;
    }

    /**
     * The events that end the stream, if this snapshot ends it: a final structured output (not the
     * one from before a follow-up), Devin waiting for the user, finished, suspended or exited; an
     * error or a suspension for limits or billing fails. Waiting ends only once Devin has moved.
     */
    static List<AssistantEvent> ending(Snapshot s, boolean moved, JsonNode baseline, Instant at) {
        JsonNode so = s.structuredOutput();
        if (moved && so != null && so.isObject() && !so.equals(baseline) && finalStatus(so.path("status").asText(""))) {
            return List.of(Events.done(at));
        }
        String status = s.status() == null ? "" : s.status();
        String detail = s.detail() == null ? "" : s.detail();
        if ("error".equals(status) || ("suspended".equals(status) && STOPPED.contains(detail))) {
            String why = "error".equals(status) ? "an error" : detail.replace('_', ' ');
            return List.of(Events.message(at, "The Devin session stopped (" + why + ") before the draft was finished."),
                    Events.failed(at, "session " + status + (detail.isBlank() ? "" : ": " + detail)));
        }
        if ("exit".equals(status)) {
            return List.of(Events.done(at));
        }
        boolean waiting = "running".equals(status) && ("waiting_for_user".equals(detail) || "finished".equals(detail));
        if (moved && (waiting || "suspended".equals(status))) {
            return List.of(Events.done(at));
        }
        return List.of();
    }

    private static boolean finalStatus(String status) {
        return WorkflowDraft.READY.equals(status) || WorkflowDraft.NEEDS_INPUT.equals(status) || WorkflowDraft.FAILED.equals(status) || "done".equals(status);
    }

    /** What the builder shows for a session status. */
    static String describe(String status, String detail) {
        String d = detail == null ? "" : detail;
        return switch (status) {
            case "new", "claimed" -> "Devin is starting";
            case "resuming" -> "Devin is resuming";
            case "running" -> switch (d) {
                case "waiting_for_user" -> "Devin is waiting for you";
                case "waiting_for_approval" -> "Devin is waiting for an approval in Devin";
                case "finished" -> "Devin finished";
                default -> "Devin is working";
            };
            case "exit" -> "Devin ended the session";
            case "error" -> "Devin stopped with an error";
            case "suspended" -> "Devin is suspended" + (d.isBlank() ? "" : " (" + d.replace('_', ' ') + ")");
            default -> "Devin is " + status.replace('_', ' ');
        };
    }

    static ProviderDraft providerDraft(JsonNode so) {
        List<String> assumptions = new ArrayList<>();
        so.path("assumptions").forEach(a -> assumptions.add(a.asText()));
        List<String> questions = new ArrayList<>();
        so.path("questions").forEach(q -> questions.add(q.isObject() ? q.path("question").asText() : q.asText()));
        return new ProviderDraft(so.path("providerYaml").asText(""), so.path("summary").asText(""), assumptions, questions);
    }

    // ---------------------------------------------------------------- playbooks

    /** The configured playbook id, else the one the last sync found or created for the macro, else none (the macro in the prompt). */
    String playbookFor(String macro, String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return synced.get(macro);
    }

    /** The organization's own playbooks (enterprise ones are read-only here). */
    Mono<List<JsonNode>> existingPlaybooks() {
        return playbookPage(null)
                .expand(p -> p.path("has_next_page").asBoolean(false) && !p.path("end_cursor").asText("").isBlank() ? playbookPage(p.path("end_cursor").asText()) : Mono.empty())
                .take(MAX_PAGES)
                .concatMapIterable(p -> {
                    List<JsonNode> items = new ArrayList<>();
                    p.path("items").forEach(items::add);
                    return items;
                })
                .filter(p -> !"enterprise".equals(p.path("access_type").asText("")))
                .collectList();
    }

    private Mono<JsonNode> playbookPage(String after) {
        return client.get().uri(b -> after == null
                ? b.path("/playbooks").queryParam("first", PAGE_SIZE).build()
                : b.path("/playbooks").queryParam("first", PAGE_SIZE).queryParam("after", "{after}").build(after))
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .retrieve()
                .bodyToMono(JsonNode.class);
    }

    Mono<PlaybookSync> sync(PlaybookFile f, List<JsonNode> existing) {
        JsonNode match = existing.stream().filter(p -> f.macro().equals(p.path("macro").asText(null))).findFirst().orElse(null);
        ObjectNode body = Events.mapper().createObjectNode();
        body.put("title", f.title());
        body.put("body", f.body());
        body.put("macro", f.macro());
        if (f.schema() != null) {
            body.set("structured_output_schema", f.schema());
        }
        if (match == null) {
            return client.post().uri("/playbooks")
                    .header(HttpHeaders.AUTHORIZATION, bearer())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .map(n -> remember(f, n.path("playbook_id").asText(""), "created"));
        }
        String id = match.path("playbook_id").asText("");
        if (same(match, f)) {
            return Mono.just(remember(f, id, "unchanged"));
        }
        return client.put().uri("/playbooks/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(n -> remember(f, n.path("playbook_id").asText(id), "updated"));
    }

    private PlaybookSync remember(PlaybookFile f, String id, String action) {
        if (id == null || id.isBlank()) {
            throw new IllegalStateException("Devin returned no playbook id for " + f.macro());
        }
        synced.put(f.macro(), id);
        return new PlaybookSync(f.file(), f.macro(), id, action);
    }

    static boolean same(JsonNode existing, PlaybookFile f) {
        JsonNode schema = existing.get("structured_output_schema");
        JsonNode theirs = schema == null || schema.isNull() ? null : schema;
        return f.title().equals(existing.path("title").asText()) && f.body().strip().equals(existing.path("body").asText("").strip())
                && Objects.equals(f.schema(), theirs);
    }

    // ---------------------------------------------------------------- prompts

    /**
     * The macro line and the DESCRIPTION, MODE, CURRENT_DRAFT and CONTEXT blocks the playbook reads.
     * MODE is {@code explain} when the user asks what the draft on the canvas does: the playbook then
     * answers in prose and leaves the draft as it is.
     */
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
        boolean hasDraft = request.currentDraft() != null && !request.currentDraft().isBlank();
        boolean edit = (request.session() != null && request.session().isEdit()) || hasDraft;
        String mode = hasDraft && !request.fromPlatform() && DraftExplainer.asks(request.text()) ? EXPLAIN_MODE
                : edit ? AssistantSession.EDIT : AssistantSession.CREATE;
        sb.append("MODE: ").append(mode).append("\n\n");
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

    static String providerPrompt(ProviderRequest request) {
        Identity who = request.who();
        String team = who == null || who.teams() == null || who.teams().isEmpty() ? "" : who.teams().get(0);
        Context ctx = request.context();
        return PROVIDER_MACRO + "\n\nDESCRIPTION:\n" + (request.description() == null ? "" : request.description().trim()) + "\n\nCONTEXT:\n"
                + "owner: " + team + "\n"
                + "requester_team: " + team + "\n"
                + "requester: " + (who == null ? "" : who.id()) + "\n"
                + "PLATFORM_API: " + (ctx == null || ctx.platformApi() == null ? "" : ctx.platformApi()) + "\n";
    }

    private static String minutes(Duration d) {
        long m = d.toMinutes();
        return m >= 1 ? m + " minute" + (m == 1 ? "" : "s") : d.toMillis() + " ms";
    }

    private Instant now() {
        return clock.instant();
    }
}
