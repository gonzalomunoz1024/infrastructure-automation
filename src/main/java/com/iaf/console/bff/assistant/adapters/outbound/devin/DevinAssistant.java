package com.iaf.console.bff.assistant.adapters.outbound.devin;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.bff.assistant.application.AssistantProperties;
import com.iaf.console.bff.assistant.domain.AssistantAnswer;
import com.iaf.console.bff.assistant.domain.AssistantException;
import com.iaf.console.bff.assistant.domain.AssistantRequest;
import com.iaf.console.bff.assistant.ports.outbound.AssistantPort;
import com.iaf.console.kit.scope.AccountScope;

import io.netty.channel.ChannelOption;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * The copilot's assistant on the firm's Devin, over its v1 REST API. A turn is one session: the
 * BFF creates it ({@code POST /v1/sessions}) with a prompt from {@code assistant/devin-prompt.txt}
 * that carries the request the BFF gathered and the answer's JSON Schema
 * ({@code assistant/answer-schema.json}, also sent as {@code structured_output_schema}), with no
 * organization secret and no knowledge ({@code secret_ids} and {@code knowledge_ids} empty) and
 * unlisted; then it reads the session every {@code poll-interval} ({@code GET /v1/session/{id}},
 * and {@code GET /v1/sessions/{id}} from the first 404 on) until it is {@code finished} or
 * {@code blocked} (waiting for a reply after it answered), and reads the answer from its
 * {@code structured_output} (or the JSON of its last message when it set none).
 *
 * <p>How a turn ends: no answer within the turn timeout is {@code assistant.timeout} (and the
 * session is ended); a call that fails, or reads that keep failing, {@code assistant.unavailable};
 * a refusal, or an answer the console cannot read, {@code assistant.refused}. The session never
 * gets a credential: the key is the BFF's, in the {@code Authorization} header, and nothing in the
 * prompt lets it call the console.
 */
@Component
@ConditionalOnProperty(prefix = "console.assistant", name = "provider", havingValue = "devin", matchIfMissing = true)
public class DevinAssistant implements AssistantPort {

    private static final Logger log = LoggerFactory.getLogger(DevinAssistant.class);

    static final String PROMPT = "assistant/devin-prompt.txt";
    static final String SCHEMA = "assistant/answer-schema.json";
    static final String CREATE = "/v1/sessions";
    /** The session path read first, as the brief names it. */
    static final String SESSION = "/v1/session/";
    /** The session path of Devin's OpenAPI document, read once the first answers 404. */
    static final String SESSIONS = "/v1/sessions/";
    /** Reads of a session that may fail in a row (5xx, 429, no answer) before the turn is unavailable. */
    static final int READ_FAILURES = 3;
    /** The largest answer read: a session's messages come with it. */
    static final int MAX_BODY = 4 * 1024 * 1024;
    static final String TAG = "console-copilot";
    static final int EXCERPT = 300;
    /** A session done: it finished, or it waits for a reply after its answer. */
    static final Set<String> DONE = Set.of("finished", "blocked");
    /** A session that stopped without finishing; what it answered so far still counts. */
    static final Set<String> STOPPED = Set.of("suspend_requested", "suspend_requested_frontend", "suspended", "stopped");

    private final WebClient web;
    private final DevinProperties devin;
    private final AssistantProperties assistant;
    private final ObjectMapper json;
    private final String template;
    private final String schemaText;
    private final JsonNode schema;
    private final AtomicReference<String> sessionPath = new AtomicReference<>(SESSION);

    @Autowired
    public DevinAssistant(WebClient.Builder builder, DevinProperties devin, AssistantProperties assistant, ObjectMapper json) {
        this(client(builder, devin), devin, assistant, json);
    }

    DevinAssistant(WebClient web, DevinProperties devin, AssistantProperties assistant, ObjectMapper json) {
        this.web = web;
        this.devin = devin;
        this.assistant = assistant;
        this.json = json;
        this.template = resource(PROMPT);
        this.schemaText = resource(SCHEMA).strip();
        try {
            this.schema = json.readTree(schemaText);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(SCHEMA + " is not JSON", e);
        }
        if (!devin.hasKey()) {
            log.warn("console.assistant.provider is devin and no key is set (CONSOLE_ASSISTANT_DEVIN_API_KEY): every copilot turn fails {}",
                    AssistantException.UNAVAILABLE);
        }
    }

    /** Devin's API with the BFF's key, through the JVM's proxy settings when there are any. */
    static WebClient client(WebClient.Builder builder, DevinProperties devin) {
        int connect = (int) Math.min(Integer.MAX_VALUE, devin.requestTimeout().toMillis());
        HttpClient http = HttpClient.create()
                .proxyWithSystemProperties()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connect)
                .responseTimeout(devin.requestTimeout());
        return builder.clone()
                .baseUrl(devin.baseUrl())
                .clientConnector(new ReactorClientHttpConnector(http))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_BODY))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + devin.apiKey())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public Mono<AssistantAnswer> answer(AccountScope scope, AssistantRequest request) {
        if (!devin.hasKey()) {
            return Mono.error(AssistantException.unavailable("the copilot has no key for its assistant"));
        }
        return Mono.defer(() -> start(request))
                .flatMap(id -> outcome(id, request.turnId()).doOnCancel(() -> end(id, "the turn ran out of time")))
                .onErrorMap(CallFailed.class, e -> new AssistantException(AssistantException.UNAVAILABLE, e.getMessage(), e))
                .timeout(assistant.turnTimeout(), Mono.error(() -> AssistantException.notWithin(assistant.turnTimeout())));
    }

    /** Creates the turn's session; its id. */
    Mono<String> start(AssistantRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prompt", prompt(request));
        body.put("title", "Console copilot " + request.turnId());
        body.put("unlisted", true);
        body.put("secret_ids", List.of());
        body.put("knowledge_ids", List.of());
        body.put("tags", request.intent() == null ? List.of(TAG) : List.of(TAG, request.intent()));
        body.put("structured_output_schema", schema);
        if (devin.maxAcuLimit() > 0) {
            body.put("max_acu_limit", devin.maxAcuLimit());
        }
        String what = "start a session";
        return send(web.post().uri(CREATE).contentType(MediaType.APPLICATION_JSON).bodyValue(body), what).map(reply -> {
            String id = body(reply, what).path("session_id").asText("");
            if (id.isBlank()) {
                throw new CallFailed(reply.status(), "the assistant started no session", null);
            }
            log.debug("copilot turn {} is Devin session {}", request.turnId(), id);
            return id;
        });
    }

    /** The prompt: the template with the answer's schema and the request as JSON. */
    String prompt(AssistantRequest request) {
        String gathered;
        try {
            gathered = json.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("the copilot could not write its request as JSON", e);
        }
        // the schema first: what the request says is never read as a placeholder
        return template.replace("{{schema}}", schemaText).replace("{{request}}", gathered);
    }

    /** Reads the session until it is done, a read now and then failing on the way. */
    Mono<AssistantAnswer> outcome(String id, String turnId) {
        AtomicInteger failures = new AtomicInteger();
        return Mono.delay(devin.pollInterval())
                .then(Mono.defer(() -> fetch(id)))
                .doOnNext(session -> failures.set(0))
                .onErrorResume(CallFailed.class, e -> {
                    if (e.passing() && failures.incrementAndGet() < READ_FAILURES) {
                        log.debug("Devin session {} of copilot turn {} could not be read, read again: {}", id, turnId, e.getMessage());
                        return Mono.empty();
                    }
                    return Mono.error(e);
                })
                .flatMap(session -> Mono.justOrEmpty(settled(id, turnId, session)))
                .repeatWhenEmpty(Integer.MAX_VALUE, repeats -> repeats);
    }

    /** The session as Devin has it now, from the path that serves it. */
    Mono<JsonNode> fetch(String id) {
        String what = "read its session";
        String path = sessionPath.get();
        return send(web.get().uri(path + "{id}", id), what).flatMap(reply -> {
            if (reply.status() == 404 && !SESSIONS.equals(path)) {
                if (sessionPath.compareAndSet(path, SESSIONS)) {
                    log.info("Devin answers 404 on {}{id}; the copilot reads sessions at {}{id} from now on", path, SESSIONS);
                }
                return send(web.get().uri(SESSIONS + "{id}", id), what).map(again -> body(again, what));
            }
            return Mono.fromCallable(() -> body(reply, what));
        });
    }

    /** The answer when the session is done; empty while it works; an error when it stopped without one. */
    private Optional<AssistantAnswer> settled(String id, String turnId, JsonNode session) {
        String status = status(session);
        if (DONE.contains(status)) {
            if ("blocked".equals(status)) {
                end(id, "it waits for a reply, which the copilot never sends");
            }
            List<String> notes = new ArrayList<>();
            Optional<AssistantAnswer> answer = answerOf(session, json, notes);
            if (!notes.isEmpty()) {
                log.info("Devin's answer to copilot turn {} had what the console does not read: {}", turnId, notes);
            }
            return Optional.of(answer.orElseThrow(() -> AssistantException.refused("blocked".equals(status)
                    ? "the assistant stopped to ask a question instead of answering"
                    : "the assistant finished without an answer the console can read")));
        }
        if ("expired".equals(status)) {
            throw AssistantException.timeout("the assistant's session expired before it answered");
        }
        if (STOPPED.contains(status)) {
            end(id, "it stopped");
            Optional<AssistantAnswer> answer = answerOf(session, json, new ArrayList<>());
            return Optional.of(answer.orElseThrow(() -> AssistantException.unavailable("the assistant's session stopped before it answered")));
        }
        return Optional.empty();
    }

    /** The session's state: {@code status_enum}, else {@code status}, in lower case. */
    static String status(JsonNode session) {
        JsonNode value = session.path("status_enum");
        if (!value.isTextual() || value.textValue().isBlank()) {
            value = session.path("status");
        }
        return value.isTextual() ? value.textValue().strip().toLowerCase(Locale.ROOT) : "";
    }

    /**
     * The answer of a session: its {@code structured_output} (an object, or JSON in text), else the
     * JSON object in its last message from Devin; empty when neither reads as an answer.
     */
    static Optional<AssistantAnswer> answerOf(JsonNode session, ObjectMapper json, List<String> notes) {
        Optional<JsonNode> written = object(session.get("structured_output"), json);
        if (written.isEmpty()) {
            written = lastMessage(session, json);
        }
        if (written.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(AssistantAnswer.read(written.get(), notes));
        } catch (IllegalArgumentException e) {
            notes.add(e.getMessage());
            return Optional.empty();
        }
    }

    /** A JSON object, given as one or as its text. */
    static Optional<JsonNode> object(JsonNode node, ObjectMapper json) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return Optional.empty();
        }
        if (node.isObject()) {
            return Optional.of(node);
        }
        if (node.isTextual()) {
            String text = node.textValue();
            int from = text.indexOf('{');
            int to = text.lastIndexOf('}');
            if (from >= 0 && to > from) {
                try {
                    JsonNode parsed = json.readTree(text.substring(from, to + 1));
                    return parsed != null && parsed.isObject() ? Optional.of(parsed) : Optional.empty();
                } catch (JsonProcessingException e) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    /** The JSON object of Devin's last message, when it wrote its answer there. */
    static Optional<JsonNode> lastMessage(JsonNode session, ObjectMapper json) {
        JsonNode messages = session.path("messages");
        for (int i = messages.size() - 1; i >= 0; i--) {
            JsonNode message = messages.get(i);
            if ("devin_message".equals(message.path("type").asText())) {
                return object(message.get("message"), json);
            }
        }
        return Optional.empty();
    }

    /** Ends a session that is no longer needed; what happens is only logged. */
    void end(String id, String why) {
        send(web.delete().uri(SESSIONS + "{id}", id), "end a session").subscribe(
                reply -> log.debug("the copilot ended Devin session {} ({}): {}", id, why, reply.status()),
                e -> log.debug("the copilot could not end Devin session {}: {}", id, e.toString()));
    }

    private Mono<Reply> send(WebClient.RequestHeadersSpec<?> request, String what) {
        return request.exchangeToMono(r -> r.bodyToMono(byte[].class).defaultIfEmpty(new byte[0]).map(b -> new Reply(r.statusCode().value(), b)))
                .timeout(devin.requestTimeout())
                .onErrorMap(e -> !(e instanceof CallFailed), e -> new CallFailed(0, "the assistant could not be reached to " + what
                        + (e instanceof TimeoutException ? " within " + devin.requestTimeout().toSeconds() + " seconds" : ""), e));
    }

    private JsonNode body(Reply reply, String what) {
        if (reply.status() / 100 != 2) {
            log.warn("Devin answered {} when the copilot tried to {}: {}", reply.status(), what, excerpt(reply.body()));
            throw new CallFailed(reply.status(), "the assistant answered " + reply.status() + " when the copilot tried to " + what, null);
        }
        try {
            JsonNode node = json.readTree(reply.body());
            if (node == null || !node.isObject()) {
                throw new CallFailed(reply.status(), "the assistant answered what the copilot cannot read when it tried to " + what, null);
            }
            return node;
        } catch (IOException e) {
            throw new CallFailed(reply.status(), "the assistant answered what the copilot cannot read when it tried to " + what, e);
        }
    }

    private static String excerpt(byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8).replaceAll("\\s+", " ").strip();
        return text.length() <= EXCERPT ? text : text.substring(0, EXCERPT) + "...";
    }

    private static String resource(String name) {
        try (InputStream in = DevinAssistant.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException(name + " is not on the class path");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** An answer of Devin's API: its status and body. */
    record Reply(int status, byte[] body) {
    }

    /** A call to Devin that got no 2xx answer, or none at all (status 0). */
    static final class CallFailed extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final int status;

        CallFailed(int status, String message, Throwable cause) {
            super(message, cause);
            this.status = status;
        }

        int status() {
            return status;
        }

        /** A failure the next read may not meet: no answer, too many requests, or an error of Devin's own. */
        boolean passing() {
            return status == 0 || status == 429 || status >= 500;
        }
    }
}
