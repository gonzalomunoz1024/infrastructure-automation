package com.iaf.platform.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;

import com.iaf.platform.common.domain.ConflictException;
import com.iaf.platform.common.domain.ForbiddenException;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.common.domain.ServiceUnavailableException;
import com.iaf.platform.common.domain.TooManyRequestsException;
import com.iaf.platform.common.tracing.TraceIds;
import com.iaf.platform.common.tracing.Traces;
import com.iaf.platform.runs.domain.InvalidInputException;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

/**
 * Central error mapping: IllegalArgumentException -> 400 (with problems or rows when the input
 * failed its schema), NotFound -> 404, Forbidden -> 403, Conflict -> 409 (with its detail),
 * ServiceUnavailable -> 503, ResponseStatusException -> its status, everything else -> 500 with a
 * generic message and a correlation id (the request's trace id when it has one) that finds the
 * cause in the log.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public Mono<ResponseEntity<Map<String, Object>>> badRequest(IllegalArgumentException ex) {
        return Mono.just(ResponseEntity.badRequest().body(body(ex.getMessage())));
    }

    /** Input that failed the workflow's schema: {@code { error, problems }} for one job, {@code { error, rows }} for a batch. */
    @ExceptionHandler(InvalidInputException.class)
    public Mono<ResponseEntity<Map<String, Object>>> invalidInput(InvalidInputException ex) {
        Map<String, Object> b = body(ex.getMessage());
        if (ex.problems() != null) {
            b.put("problems", ex.problems());
        }
        if (ex.rows() != null) {
            b.put("rows", ex.rows());
        }
        return Mono.just(ResponseEntity.badRequest().body(b));
    }

    @ExceptionHandler(ServerWebInputException.class)
    public Mono<ResponseEntity<Map<String, Object>>> badInput(ServerWebInputException ex) {
        return Mono.just(ResponseEntity.badRequest().body(body(ex.getReason())));
    }

    @ExceptionHandler(NotFoundException.class)
    public Mono<ResponseEntity<Map<String, Object>>> notFound(NotFoundException ex) {
        return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).body(body(ex.getMessage())));
    }

    @ExceptionHandler(ForbiddenException.class)
    public Mono<ResponseEntity<Map<String, Object>>> forbidden(ForbiddenException ex) {
        return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).body(body(ex.getMessage())));
    }

    @ExceptionHandler(ConflictException.class)
    public Mono<ResponseEntity<Map<String, Object>>> conflict(ConflictException ex) {
        Map<String, Object> b = body(ex.getMessage());
        if (ex.detail() != null) {
            b.put("detail", ex.detail());
        }
        return Mono.just(ResponseEntity.status(HttpStatus.CONFLICT).body(b));
    }

    @ExceptionHandler(ServiceUnavailableException.class)
    public Mono<ResponseEntity<Map<String, Object>>> unavailable(ServiceUnavailableException ex) {
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body(ex.getMessage())));
    }

    /** Over a quota: 429 with {@code Retry-After} in seconds, and the same number in the body. */
    @ExceptionHandler(TooManyRequestsException.class)
    public Mono<ResponseEntity<Map<String, Object>>> tooManyRequests(TooManyRequestsException ex) {
        Map<String, Object> b = body(ex.getMessage());
        b.put("retryAfter", ex.retryAfterSeconds());
        return Mono.just(ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds()))
                .body(b));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public Mono<ResponseEntity<Map<String, Object>>> status(ResponseStatusException ex) {
        return Mono.just(ResponseEntity.status(ex.getStatusCode()).body(body(ex.getReason())));
    }

    /** A 500 says nothing of its cause (a message may name a host, a path or a query); the id finds it in the log. */
    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<Map<String, Object>>> generic(Exception ex, ServerWebExchange exchange) {
        return Mono.deferContextual(ctx -> {
            String correlationId = correlationId(ctx, exchange);
            log.error("unhandled [{}]", correlationId, ex);
            Map<String, Object> b = body("unexpected error");
            b.put("correlationId", correlationId);
            return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(b));
        });
    }

    /** The request's trace id: of its observation, else of its {@code traceparent}; a fresh one when it has neither. */
    static String correlationId(ContextView ctx, ServerWebExchange exchange) {
        String traced = Traces.traceIdOf(Traces.current(ctx));
        if (traced != null) {
            return traced;
        }
        String traceparent = exchange == null ? null : exchange.getRequest().getHeaders().getFirst(TraceIds.TRACEPARENT);
        return TraceIds.parse(traceparent).map(TraceIds.Parent::traceId).orElseGet(TraceIds::newTraceId);
    }

    private static Map<String, Object> body(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", msg == null ? "unexpected error" : msg);
        return m;
    }
}
