package com.iaf.platform.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;

import com.iaf.platform.common.domain.ConflictException;
import com.iaf.platform.common.domain.ForbiddenException;
import com.iaf.platform.common.domain.NotFoundException;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Central error mapping: IllegalArgumentException -> 400, NotFound -> 404, Forbidden -> 403,
 * Conflict -> 409 (with its detail), ResponseStatusException -> its status, everything else -> 500.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public Mono<ResponseEntity<Map<String, Object>>> badRequest(IllegalArgumentException ex) {
        return Mono.just(ResponseEntity.badRequest().body(body(ex.getMessage())));
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
