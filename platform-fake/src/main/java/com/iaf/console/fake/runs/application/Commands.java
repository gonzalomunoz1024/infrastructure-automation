package com.iaf.console.fake.runs.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.fake.common.ApiException;
import com.iaf.console.fake.common.FakeClock;
import com.iaf.console.fake.common.Json;
import com.iaf.console.fake.identity.application.Callers;
import com.iaf.console.fake.identity.domain.Caller;
import com.iaf.console.fake.identity.domain.Delegation;
import com.iaf.console.fake.identity.domain.RequestContext;
import com.iaf.console.fake.runs.domain.CommandResult;
import com.iaf.console.fake.runs.domain.Fingerprints;

import reactor.core.publisher.Mono;

/**
 * Runs a command once per {@code Idempotency-Key} (P-21): the key is scoped to the caller, the
 * fingerprint covers the method, path, body and context headers, a replay answers the first
 * result with {@code Idempotent-Replay: true}, and a key reused with another request is a 422.
 */
public final class Commands {

    /** How long a key is remembered. */
    public static final Duration TTL = Duration.ofHours(24);
    public static final String IDEMPOTENT_REPLAY = "Idempotent-Replay";

    /** What a key remembers: the fingerprint and the result, or nothing yet while the command runs. */
    private record Entry(String fingerprint, CommandResult result, Instant expiresAt) {

        boolean inFlight() {
            return result == null;
        }
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final FakeClock clock;

    public Commands(FakeClock clock) {
        this.clock = clock;
    }

    /** Reads the JSON body (an empty body is an empty object), runs the command once per key, writes the result. */
    public Mono<ServerResponse> handle(ServerRequest request, Function<JsonNode, CommandResult> command) {
        return request.bodyToMono(String.class).defaultIfEmpty("")
                .flatMap(text -> Mono.fromCallable(() -> execute(request, parse(text), command)))
                .flatMap(Commands::write);
    }

    /** Runs the command for a body already read. */
    public CommandResult execute(ServerRequest request, JsonNode body, Function<JsonNode, CommandResult> command) {
        Caller caller = Callers.of(request);
        RequestContext context = Callers.context(request);
        String key = context.idempotencyKey();
        if (key == null) {
            return command.apply(body);
        }
        Delegation delegation = caller.delegation();
        String fingerprint = Fingerprints.of(request.method().name(), request.path(), body,
                delegation == null ? null : delegation.userId(), delegation == null ? null : delegation.accountId(),
                context.wfrn(), context.consoleRequestId(), context.labels());
        String scoped = caller.id() + "\n" + key;
        Instant now = clock.now();
        prune(now);
        Entry marker = new Entry(fingerprint, null, now.plus(TTL));
        Entry existing = entries.putIfAbsent(scoped, marker);
        if (existing != null) {
            if (!existing.fingerprint().equals(fingerprint)) {
                throw ApiException.unprocessable("idempotency.mismatch", Map.of("key", key));
            }
            if (existing.inFlight()) {
                throw ApiException.conflict("idempotency.inFlight");
            }
            return existing.result().withHeader(IDEMPOTENT_REPLAY, "true");
        }
        CommandResult result;
        try {
            result = command.apply(body);
        } catch (RuntimeException e) {
            entries.remove(scoped, marker);
            throw e;
        }
        entries.put(scoped, new Entry(fingerprint, result, now.plus(TTL)));
        return result;
    }

    /** How many keys are remembered now. */
    public int size() {
        return entries.size();
    }

    private void prune(Instant now) {
        if (entries.size() > 10_000) {
            entries.values().removeIf(entry -> entry.expiresAt().isBefore(now));
        }
    }

    public static JsonNode parse(String text) {
        if (text == null || text.isBlank()) {
            return Json.object();
        }
        try {
            JsonNode body = Json.read(text);
            return body == null || body.isNull() ? Json.object() : body;
        } catch (RuntimeException e) {
            throw ApiException.badRequest("body.invalidJson");
        }
    }

    static Mono<ServerResponse> write(CommandResult result) {
        ServerResponse.BodyBuilder builder = ServerResponse.status(result.status());
        result.headers().forEach(builder::header);
        if (result.body() == null) {
            return builder.build();
        }
        return builder.contentType(MediaType.APPLICATION_JSON).bodyValue(result.body());
    }
}
