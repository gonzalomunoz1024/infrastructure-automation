package com.iaf.console.kit.errors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;

import com.iaf.console.kit.correlation.Correlation;

import reactor.core.publisher.Mono;

/**
 * The one error mapping of a console service: IllegalArgumentException is a 400 (with problems
 * when the input failed its schema), NotFound a 404, Forbidden a 403, Conflict a 409, TooManyRequests
 * a 429 with {@code Retry-After}, a ResponseStatusException its status, and anything else a 500
 * that says nothing of its cause and carries the correlation id that finds it in the log.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ProblemExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemExceptionHandler.class);

    @ExceptionHandler(InvalidInputException.class)
    public Mono<ResponseEntity<Errors.Body>> invalidInput(InvalidInputException ex) {
        return Mono.just(ResponseEntity.badRequest().body(Errors.withProblems(ex.getMessage(), ex.problems())));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Mono<ResponseEntity<Errors.Body>> badRequest(IllegalArgumentException ex) {
        return Mono.just(ResponseEntity.badRequest().body(Errors.of(ex.getMessage())));
    }

    @ExceptionHandler(ServerWebInputException.class)
    public Mono<ResponseEntity<Errors.Body>> badInput(ServerWebInputException ex) {
        return Mono.just(ResponseEntity.badRequest().body(Errors.of(ex.getReason())));
    }

    @ExceptionHandler(NotFoundException.class)
    public Mono<ResponseEntity<Errors.Body>> notFound(NotFoundException ex) {
        return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).body(Errors.of(ex.getMessage())));
    }

    @ExceptionHandler(ForbiddenException.class)
    public Mono<ResponseEntity<Errors.Body>> forbidden(ForbiddenException ex) {
        return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).body(Errors.of(ex.getMessage())));
    }

    @ExceptionHandler(ConflictException.class)
    public Mono<ResponseEntity<Errors.Body>> conflict(ConflictException ex) {
        return Mono.just(ResponseEntity.status(HttpStatus.CONFLICT).body(Errors.conflict(ex.getMessage(), ex.detail())));
    }

    @ExceptionHandler(TooManyRequestsException.class)
    public Mono<ResponseEntity<Errors.Body>> tooManyRequests(TooManyRequestsException ex) {
        return Mono.just(ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds()))
                .body(Errors.tooManyRequests(ex.getMessage(), ex.retryAfterSeconds())));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public Mono<ResponseEntity<Errors.Body>> status(ResponseStatusException ex) {
        return Mono.just(ResponseEntity.status(ex.getStatusCode()).body(Errors.of(ex.getReason())));
    }

    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<Errors.Body>> unexpected(Exception ex, ServerWebExchange exchange) {
        return Mono.deferContextual(ctx -> {
            String correlationId = Correlation.id(ctx).orElseGet(() -> Correlation.of(exchange));
            log.error("unhandled [{}]", correlationId, ex);
            return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Errors.unexpected(correlationId)));
        });
    }
}
