package com.iaf.console.identity.common.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;

import org.reactivestreams.Publisher;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.identity.common.auth.Caller;
import com.iaf.console.kit.correlation.Correlation;
import com.iaf.console.kit.errors.Errors;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The contract's {@code Idempotency-Key}: a write that carries one is run once per caller, method,
 * path and key for 24 hours; a retry with the same body answers the stored result (with
 * {@code Idempotent-Replay: true}), a retry with another body is 409, and a retry while the first
 * call still runs is 409 too. Bodies up to one megabyte are hashed; larger ones are refused.
 */
public final class IdempotencyWebFilter implements WebFilter, Ordered {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAY_HEADER = "Idempotent-Replay";
    static final Duration TTL = Duration.ofHours(24);
    private static final int MAX_BODY = 1024 * 1024;

    private final IdempotencyPort store;
    private final Clock clock;
    private final ObjectMapper json;

    public IdempotencyWebFilter(IdempotencyPort store, Clock clock, ObjectMapper json) {
        this.store = store;
        this.clock = clock;
        this.json = json;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 200;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String key = request.getHeaders().getFirst(HEADER);
        HttpMethod method = request.getMethod();
        if (key == null || key.isBlank() || method == HttpMethod.GET || method == HttpMethod.HEAD || method == HttpMethod.OPTIONS) {
            return chain.filter(exchange);
        }
        if (key.length() > 255) {
            return problem(exchange, HttpStatus.BAD_REQUEST, "Idempotency-Key must be at most 255 characters");
        }
        Object caller = exchange.getAttribute(Caller.ATTRIBUTE);
        String actor = caller instanceof Caller c ? c.actor() : "anonymous";
        String scope = actor + " " + method.name() + " " + request.getPath().pathWithinApplication().value() + " " + key.trim();
        return DataBufferUtils.join(request.getBody(), MAX_BODY)
                .map(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    DataBufferUtils.release(buffer);
                    return bytes;
                })
                .defaultIfEmpty(new byte[0])
                .onErrorResume(e -> Mono.empty())
                .switchIfEmpty(Mono.defer(() -> Mono.error(new TooLarge())))
                .flatMap(body -> store.reserve(scope, sha256(body), clock.instant(), TTL)
                        .flatMap(reservation -> switch (reservation.outcome()) {
                            case RESERVED -> runAndStore(exchange, chain, scope, body);
                            case REPLAY -> replay(exchange, reservation.record());
                            case IN_FLIGHT -> problem(exchange, HttpStatus.CONFLICT, "a call with this Idempotency-Key is still running");
                            case MISMATCH -> problem(exchange, HttpStatus.CONFLICT, "this Idempotency-Key was used with another body");
                        }))
                .onErrorResume(TooLarge.class, e -> problem(exchange, HttpStatus.PAYLOAD_TOO_LARGE, "a body with an Idempotency-Key is at most one megabyte"));
    }

    private Mono<Void> runAndStore(ServerWebExchange exchange, WebFilterChain chain, String scope, byte[] body) {
        ServerHttpRequest replayable = new ServerHttpRequestDecorator(exchange.getRequest()) {
            @Override
            public Flux<DataBuffer> getBody() {
                return Flux.just(exchange.getResponse().bufferFactory().wrap(body));
            }
        };
        CapturingResponse response = new CapturingResponse(exchange.getResponse());
        ServerWebExchange mutated = exchange.mutate().request(replayable).response(response).build();
        return chain.filter(mutated)
                .then(Mono.defer(() -> {
                    HttpStatus status = response.getStatusCode() == null ? HttpStatus.OK : HttpStatus.valueOf(response.getStatusCode().value());
                    if (status.is5xxServerError()) {
                        return store.release(scope);
                    }
                    MediaType type = response.getHeaders().getContentType();
                    return store.complete(scope, status.value(), type == null ? null : type.toString(), response.captured());
                }))
                .onErrorResume(e -> store.release(scope).then(Mono.error(e)));
    }

    private Mono<Void> replay(ServerWebExchange exchange, IdempotencyRecord record) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.valueOf(record.status()));
        response.getHeaders().set(REPLAY_HEADER, "true");
        if (record.contentType() != null) {
            response.getHeaders().set(HttpHeaders.CONTENT_TYPE, record.contentType());
        }
        byte[] body = record.body() == null ? new byte[0] : record.body();
        if (body.length == 0) {
            return response.setComplete();
        }
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    private Mono<Void> problem(ServerWebExchange exchange, HttpStatus status, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body;
        try {
            body = json.writeValueAsBytes(Errors.of(message).withCorrelationId(Correlation.of(exchange)));
        } catch (JsonProcessingException e) {
            body = "{\"error\":\"idempotency\"}".getBytes(StandardCharsets.UTF_8);
        }
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing", e);
        }
    }

    private static final class TooLarge extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    /** Captures the bytes a handler writes so a retry can answer the same. */
    private static final class CapturingResponse extends ServerHttpResponseDecorator {

        private final java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();

        CapturingResponse(ServerHttpResponse delegate) {
            super(delegate);
        }

        @Override
        public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
            return super.writeWith(Flux.from(body).map(buffer -> {
                byte[] bytes = new byte[buffer.readableByteCount()];
                buffer.read(bytes);
                captured.writeBytes(bytes);
                DataBufferUtils.release(buffer);
                return bufferFactory().wrap(bytes);
            }));
        }

        @Override
        public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
            return writeWith(Flux.from(body).flatMapSequential(Flux::from));
        }

        byte[] captured() {
            return captured.toByteArray();
        }
    }
}
