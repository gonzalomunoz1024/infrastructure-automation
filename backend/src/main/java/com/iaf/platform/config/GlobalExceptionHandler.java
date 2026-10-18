package com.iaf.platform.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;

import com.iaf.platform.common.domain.ConflictException;
import com.iaf.platform.common.domain.ForbiddenException;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.common.domain.TooManyRequestsException;
import com.iaf.platform.runs.domain.InvalidInputException;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Central error mapping: IllegalArgumentException -> 400 (with problems or rows when the input
 * failed its schema), NotFound -> 404, Forbidden -> 403, Conflict -> 409 (with its detail),
 * ResponseStatusException -> its status, everything else -> 500.
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

    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<Map<String, Object>>> generic(Exception ex) {
        log.error("unhandled", ex);
        return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body(ex.getMessage())));
    }

    private static Map<String, Object> body(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", msg == null ? "unexpected error" : msg);
        return m;
    }
}
