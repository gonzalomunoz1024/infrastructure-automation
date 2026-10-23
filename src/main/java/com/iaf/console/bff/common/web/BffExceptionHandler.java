package com.iaf.console.bff.common.web;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

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
import com.iaf.console.bff.common.errors.CodedConflictException;
import com.iaf.console.bff.resources.domain.GoneException;
import com.iaf.console.bff.session.application.CurrentSession;
import com.iaf.console.bff.session.domain.NoConsoleAccountException;
import com.iaf.console.bff.session.domain.ReturnTo;
import com.iaf.console.bff.session.domain.StepUpRequiredException;
import com.iaf.console.kit.correlation.Correlation;
import com.iaf.console.kit.errors.Errors;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.platform.client.PlatformException;

import reactor.core.publisher.Mono;

/**
 * The BFF's own error mapping, ahead of the kit's: no session is 401; a refusal carries its
 * {@code denied} block; a step-up answers 403 {@code step-up.required} with where to sign in again; the platform's
 * answers map as the contract says (400 with problems, 404, 409, 429 with Retry-After, 403 as a
 * platform denial, anything else 502 with the platform's correlation id); a console service that
 * answered an error, or could not be reached, is 502, one that did not answer in time 504.
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

    /** A step-up refusal's body: the code, the sentence, and where to sign in again. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StepUpBody(String error, String message, String correlationId, Map<String, Object> detail) {
    }

    /**
     * 403 {@code step-up.required}: {@code detail.login} is the sign-in path with
     * {@code stepUp=true}, and with {@code returnTo} when the page's address (the {@code Referer},
     * which never carries a hash route) reduces to a console path other than the root; a console
     * with hash routes sets its own {@code returnTo}.
     */
    @ExceptionHandler(StepUpRequiredException.class)
    public Mono<ResponseEntity<StepUpBody>> stepUp(StepUpRequiredException ex, ServerWebExchange exchange) {
        String returnTo = returnPath(exchange.getRequest().getHeaders().getFirst(HttpHeaders.REFERER));
        String login = "/auth/login?stepUp=true" + (returnTo == null ? "" : "&returnTo=" + URLEncoder.encode(returnTo, StandardCharsets.UTF_8));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("stepUp", true);
        detail.put("action", ex.action());
        detail.put("login", login);
        return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new StepUpBody(StepUpRequiredException.CODE, ex.getMessage(), Correlation.of(exchange), detail)));
    }

    /** The path, query and fragment of a page address, when it is a safe console path other than the root. */
    static String returnPath(String referer) {
        if (referer == null || referer.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(referer.strip());
            String path = (uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath())
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())
                    + (uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment());
            return ReturnTo.isSafe(path) && !ReturnTo.DEFAULT.equals(path) ? path : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** A console service that did not answer in time: 504. */
    @ExceptionHandler(TimeoutException.class)
    public Mono<ResponseEntity<Errors.Body>> timedOut(TimeoutException ex, ServerWebExchange exchange) {
        String correlationId = Correlation.of(exchange);
        log.warn("upstream timed out [{}]: {}", correlationId, ex.getMessage());
        return Mono.just(ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .body(new Errors.Body("upstream service timed out", null, correlationId, null, null)));
    }

    /** A refusal with a code, the sentence, problems and detail: 409 {@code dependents.exist} names the dependents, {@code job.not-failed} the job's status. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CodedBody(String error, String message, List<Problem> problems, String correlationId, Object detail) {
    }

    @ExceptionHandler(CodedConflictException.class)
    public Mono<ResponseEntity<CodedBody>> codedConflict(CodedConflictException ex, ServerWebExchange exchange) {
        return Mono.just(ResponseEntity.status(HttpStatus.CONFLICT).body(new CodedBody(ex.code(), ex.getMessage(),
                List.of(Problem.error(ex.code(), null, ex.getMessage())), Correlation.of(exchange), ex.detail())));
    }

    /** A deleted resource: 410 with its tombstone as the detail, so the page can say until when the name stays reserved. */
    @ExceptionHandler(GoneException.class)
    public Mono<ResponseEntity<Errors.Body>> gone(GoneException ex, ServerWebExchange exchange) {
        return Mono.just(ResponseEntity.status(HttpStatus.GONE).body(new Errors.Body(ex.getMessage(), null, Correlation.of(exchange), null, ex.tombstone())));
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
