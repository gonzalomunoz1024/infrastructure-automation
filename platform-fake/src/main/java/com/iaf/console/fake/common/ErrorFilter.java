package com.iaf.console.fake.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.HandlerFilterFunction;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;

import com.iaf.console.kit.correlation.Correlation;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.errors.Errors;
import com.iaf.console.kit.errors.ForbiddenException;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.errors.TooManyRequestsException;

import reactor.core.publisher.Mono;

/**
 * Turns the exceptions of a handler into the platform's error bodies: the kit's exceptions and
 * {@link ApiException} to their statuses, bad JSON to 400, anything else to a 500 that names only
 * the correlation id.
 */
public final class ErrorFilter implements HandlerFilterFunction<ServerResponse, ServerResponse> {

    private static final Logger log = LoggerFactory.getLogger(ErrorFilter.class);

    @Override
    public Mono<ServerResponse> filter(ServerRequest request, HandlerFunction<ServerResponse> next) {
        return Mono.defer(() -> next.handle(request)).onErrorResume(e -> answer(request, e));
    }

    static Mono<ServerResponse> answer(ServerRequest request, Throwable e) {
        if (e instanceof ApiException api) {
            if (api.body() != null) {
                return Http.json(api.status(), api.body());
            }
            ServerResponse.BodyBuilder builder = ServerResponse.status(api.status()).contentType(MediaType.APPLICATION_JSON);
            if (api.retryAfter() != null) {
                builder = builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(api.retryAfter()));
                return builder.bodyValue(Errors.tooManyRequests(api.getMessage(), api.retryAfter()));
            }
            return builder.bodyValue(api.detail() == null ? Errors.of(api.getMessage()) : Errors.conflict(api.getMessage(), api.detail()));
        }
        if (e instanceof InvalidInputException invalid) {
            return Http.error(400, invalid.getMessage(), invalid.problems());
        }
        if (e instanceof NotFoundException) {
            return Http.error(404, e.getMessage());
        }
        if (e instanceof ForbiddenException) {
            return Http.error(403, e.getMessage());
        }
        if (e instanceof ConflictException conflict) {
            return Http.json(409, Errors.conflict(conflict.getMessage(), conflict.detail()));
        }
        if (e instanceof TooManyRequestsException tooMany) {
            return ServerResponse.status(429).contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(tooMany.retryAfterSeconds()))
                    .bodyValue(Errors.tooManyRequests(tooMany.getMessage(), tooMany.retryAfterSeconds()));
        }
        if (e instanceof ServerWebInputException input) {
            return Http.error(400, input.getReason() == null ? "bad input" : input.getReason());
        }
        if (e instanceof IllegalArgumentException) {
            return Http.error(400, e.getMessage() == null ? "bad input" : e.getMessage());
        }
        if (e instanceof ResponseStatusException status) {
            return Http.error(status.getStatusCode().value(), status.getReason() == null ? status.getStatusCode().toString() : status.getReason());
        }
        String correlationId = Correlation.of(request.exchange());
        log.error("unhandled [{}] {} {}", correlationId, request.method(), request.path(), e);
        return Http.json(500, Errors.unexpected(correlationId));
    }
}
