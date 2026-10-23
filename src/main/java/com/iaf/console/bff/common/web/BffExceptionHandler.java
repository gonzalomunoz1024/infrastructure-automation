package com.iaf.console.bff.common.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ServerWebExchange;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.iaf.console.bff.access.domain.Denial;
import com.iaf.console.bff.access.domain.DeniedException;
import com.iaf.console.bff.session.application.CurrentSession;
import com.iaf.console.bff.session.domain.NoConsoleAccountException;
import com.iaf.console.bff.session.domain.StepUpRequiredException;
import com.iaf.console.kit.correlation.Correlation;
import com.iaf.console.kit.errors.Errors;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.platform.client.PlatformException;

import reactor.core.publisher.Mono;

/**
 * The BFF's own error mapping, ahead of the kit's: no session is 401; a refusal carries its
 * {@code denied} block; a step-up answers 403 with where to re-authenticate; the platform's
 * answers map as the contract says (400 with problems, 404, 409, 429 with Retry-After, 403 as a
 * platform denial, anything else 502 with the platform's correlation id); a console service that
 * answered an error is 502.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class BffExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(BffExceptionHandler.class);

    /** The error body with a {@code denied} block. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DeniedBody(String error, String correlationId, Denial denied) {
    }

    @ExceptionHandler(CurrentSession.NoSessionException.class)
    public Mono<ResponseEntity<Errors.Body>> noSession(CurrentSession.NoSessionException ex) {
        return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Errors.of(ex.getMessage())));
    }

    @ExceptionHandler(NoConsoleAccountException.class)
    public Mono<ResponseEntity<Errors.Body>> noAccount(NoConsoleAccountException ex) {
        return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).body(Errors.of(ex.getMessage())));
    }

    @ExceptionHandler(DeniedException.class)
    public Mono<ResponseEntity<DeniedBody>> denied(DeniedException ex, ServerWebExchange exchange) {
        return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).body(new DeniedBody(ex.getMessage(), Correlation.of(exchange), ex.denial())));
    }

    @ExceptionHandler(StepUpRequiredException.class)
    public Mono<ResponseEntity<Errors.Body>> stepUp(StepUpRequiredException ex, ServerWebExchange exchange) {
        String returnTo = exchange.getRequest().getHeaders().getFirst("Referer");
        String login = "/auth/login?stepUp=true" + (returnTo == null ? "" : "&returnTo=" + URLEncoder.encode(returnTo, StandardCharsets.UTF_8));
        Map<String, Object> detail = Map.of("stepUp", true, "action", ex.action(), "login", login);
        return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).body(new Errors.Body(ex.getMessage(), null, Correlation.of(exchange), null, detail)));
    }

    @ExceptionHandler(PlatformException.class)
    public Mono<ResponseEntity<Object>> platform(PlatformException ex, ServerWebExchange exchange) {
        String correlationId = ex.correlationId() == null ? Correlation.of(exchange) : ex.correlationId();
        List<Problem> problems = ex.problems() == null ? List.of() : ex.problems().stream()
                .map(p -> new Problem("warning".equals(p.level()) ? Problem.Level.WARNING : Problem.Level.ERROR, p.code(), p.step(), p.field(), p.message(), null))
                .toList();
        switch (ex.status()) {
            case 400:
                return Mono.just(ResponseEntity.badRequest().body(new Errors.Body(ex.errorText(), problems.isEmpty() ? null : problems, correlationId, null, null)));
            case 404:
                return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).body(Errors.of(ex.errorText())));
            case 409:
                return Mono.just(ResponseEntity.status(HttpStatus.CONFLICT).body(new Errors.Body(ex.errorText(), null, correlationId, null, null)));
            case 429:
                int retryAfter = ex.retryAfterSeconds() == null ? 5 : ex.retryAfterSeconds();
                return Mono.just(ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter))
                        .body(Errors.tooManyRequests(ex.errorText(), retryAfter)));
            case 403:
                Denial denial = Denial.byPlatform(ex.operation() == null ? "platform:call" : "platform:" + ex.operation().name().toLowerCase(), null,
                        ex.errorText(), null);
                return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).body(new DeniedBody(ex.errorText(), correlationId, denial)));
            default:
                log.warn("platform answered {} [{}]: {}", ex.status(), correlationId, ex.errorText());
                return Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(new Errors.Body("platform: " + ex.errorText(), null, correlationId, null, null)));
        }
    }

    @ExceptionHandler(WebClientResponseException.class)
    public Mono<ResponseEntity<Errors.Body>> upstream(WebClientResponseException ex, ServerWebExchange exchange) {
        String correlationId = Correlation.of(exchange);
        log.warn("upstream answered {} [{}]: {}", ex.getStatusCode().value(), correlationId, ex.getResponseBodyAsString());
        return Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new Errors.Body("upstream service answered " + ex.getStatusCode().value(), null, correlationId, null, null)));
    }

    @ExceptionHandler(WebClientRequestException.class)
    public Mono<ResponseEntity<Errors.Body>> unreachable(WebClientRequestException ex, ServerWebExchange exchange) {
        String correlationId = Correlation.of(exchange);
        log.warn("upstream unreachable [{}]: {}", correlationId, ex.getMessage());
        return Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new Errors.Body("upstream service unreachable", null, correlationId, null, null)));
    }
}
