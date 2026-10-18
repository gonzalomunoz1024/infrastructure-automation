package com.iaf.platform.gateway.adapters.inbound;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.reactivestreams.Publisher;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.platform.catalog.domain.ReservedNouns;
import com.iaf.platform.common.domain.ConflictException;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.gateway.application.IdempotencyHandler;
import com.iaf.platform.gateway.application.IdempotencyHandler.Proceed;
import com.iaf.platform.gateway.application.IdempotencyHandler.Replay;
import com.iaf.platform.gateway.domain.IdempotencyKeys;
import com.iaf.platform.gateway.domain.StoredAnswer;
import com.iaf.platform.identity.adapters.inbound.IdentityWebFilter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * {@code Idempotency-Key} on the requests that start jobs: {@code POST} on a generated route,
 * {@code POST /api/v1/jobs} and {@code POST /api/v1/jobs:batch}. Runs after the identity filter
 * (keys are scoped to the caller) and before the gateway. The first request with a key runs and
 * its answer is kept when it succeeds; the same key and request again gets that answer with
 * {@code Idempotent-Replay: true}; the same key with another request, or while the first is still
 * running, is a 409. Requests without the header, and requests without an identity to scope the
 * key to, pass straight through.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 15)
@RequiredArgsConstructor
public class IdempotencyWebFilter implements WebFilter {

    /** The platform routes that start jobs and take a key, besides every generated route. */
    public static final List<String> JOB_ROUTES = List.of("/api/v1/jobs", "/api/v1/jobs:batch");
    /** Response headers a replay repeats. */
    static final List<String> REPEATED = List.of(HttpHeaders.CONTENT_TYPE, HttpHeaders.LOCATION, "Deprecation", "Sunset", HttpHeaders.LINK);
    /** The header that pins a workflow version, part of what makes two requests the same. */
    static final String VERSION_HEADER = "X-Workflow-Version";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final IdempotencyHandler idempotency;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String key = request.getHeaders().getFirst(IdempotencyKeys.HEADER);
        String path = request.getPath().pathWithinApplication().value();
        if (key == null || !HttpMethod.POST.equals(request.getMethod()) || !applies(path)) {
            return chain.filter(exchange);
        }
        Identity who = exchange.getAttribute(IdentityWebFilter.ATTRIBUTE);
        if (who == null) {
            // keys are scoped to a caller; without one there is nobody to scope it to, so the request runs without it
            log.debug("{} on {} without an identity: the key is ignored", IdempotencyKeys.HEADER, path);
            return chain.filter(exchange);
        }
        String caller = who.id();
        return DataBufferUtils.join(request.getBody())
                .map(IdempotencyWebFilter::bytes)
                .defaultIfEmpty(new byte[0])
                .flatMap(body -> {
                    String fingerprint = IdempotencyKeys.fingerprint("POST", path, request.getURI().getRawQuery(),
                            request.getHeaders().getFirst(VERSION_HEADER), body);
                    return idempotency.claim(caller, key, fingerprint).flatMap(claim -> claim instanceof Replay r
                            ? replay(exchange, r.answer())
                            : proceed(exchange, chain, body, (Proceed) claim));
                })
                .onErrorResume(ConflictException.class, e -> error(exchange, HttpStatus.CONFLICT, e.getMessage()))
                .onErrorResume(IllegalArgumentException.class, e -> error(exchange, HttpStatus.BAD_REQUEST, e.getMessage()));
    }

    /** {@code POST /api/v1/jobs}, {@code /api/v1/jobs:batch} and every path the gateway may serve. */
    public static boolean applies(String path) {
        if (path == null) {
            return false;
        }
        String p = path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;
        return JOB_ROUTES.contains(p) || ReservedNouns.isGenerated(p);
    }

    private Mono<Void> proceed(ServerWebExchange exchange, WebFilterChain chain, byte[] body, Proceed claim) {
        AtomicBoolean settled = new AtomicBoolean();
        ServerHttpRequest request = new ServerHttpRequestDecorator(exchange.getRequest()) {
            @Override
            public Flux<DataBuffer> getBody() {
                return Flux.defer(() -> Flux.just(exchange.getResponse().bufferFactory().wrap(body)));
            }
        };
        ServerHttpResponse response = new ServerHttpResponseDecorator(exchange.getResponse()) {
            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> written) {
                return DataBufferUtils.join(Flux.from(written))
                        .map(IdempotencyWebFilter::bytes)
                        .defaultIfEmpty(new byte[0])
                        .flatMap(bytes -> settle(settled, claim, answer(getDelegate(), bytes))
                                .then(super.writeWith(Mono.just(bufferFactory().wrap(bytes)))));
            }

            @Override
            public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> written) {
                return idempotencyRelease(settled, claim).then(super.writeAndFlushWith(written));   // a stream is never kept
            }

            @Override
            public Mono<Void> setComplete() {
                return settle(settled, claim, answer(getDelegate(), new byte[0])).then(super.setComplete());
            }
        };
        return chain.filter(exchange.mutate().request(request).response(response).build())
                .onErrorResume(e -> idempotencyRelease(settled, claim).then(Mono.error(e)))
                .then(Mono.defer(() -> idempotencyRelease(settled, claim)))
                .doOnCancel(() -> idempotencyRelease(settled, claim).subscribe());
    }

    private Mono<Void> settle(AtomicBoolean settled, Proceed claim, StoredAnswer answer) {
        return settled.compareAndSet(false, true) ? idempotency.complete(claim.caller(), claim.key(), answer) : Mono.empty();
    }

    private Mono<Void> idempotencyRelease(AtomicBoolean settled, Proceed claim) {
        return settled.compareAndSet(false, true) ? idempotency.release(claim.caller(), claim.key()) : Mono.empty();
    }

    static StoredAnswer answer(ServerHttpResponse response, byte[] body) {
        HttpStatusCode status = response.getStatusCode();
        Map<String, String> headers = new LinkedHashMap<>();
        for (String h : REPEATED) {
            String v = response.getHeaders().getFirst(h);
            if (v != null) {
                headers.put(h, v);
            }
        }
        return new StoredAnswer(status == null ? 200 : status.value(), headers, new String(body, StandardCharsets.UTF_8));
    }

    private static Mono<Void> replay(ServerWebExchange exchange, StoredAnswer answer) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatusCode.valueOf(answer.status()));
        answer.headers().forEach((k, v) -> response.getHeaders().set(k, v));
        response.getHeaders().set(IdempotencyKeys.REPLAY_HEADER, "true");
        byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
        response.getHeaders().setContentLength(bytes.length);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }

    private static Mono<Void> error(ServerWebExchange exchange, HttpStatus status, String message) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.empty();
        }
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] bytes;
        try {
            bytes = JSON.writeValueAsBytes(Map.of("error", message == null ? "unexpected error" : message));
        } catch (JsonProcessingException e) {
            bytes = "{\"error\":\"unexpected error\"}".getBytes(StandardCharsets.UTF_8);
        }
        response.getHeaders().setContentLength(bytes.length);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }

    private static byte[] bytes(DataBuffer buffer) {
        try {
            byte[] bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            return bytes;
        } finally {
            DataBufferUtils.release(buffer);
        }
    }
}
