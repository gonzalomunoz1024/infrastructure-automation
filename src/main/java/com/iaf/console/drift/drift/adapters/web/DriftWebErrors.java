package com.iaf.console.drift.drift.adapters.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.iaf.console.drift.drift.application.WebhookService;
import com.iaf.console.drift.drift.ports.outbound.StateServicePort;
import com.iaf.console.drift.drift.ports.outbound.WorkflowStarterPort;
import com.iaf.console.kit.errors.Errors;
import com.iaf.console.platform.client.PlatformException;

import reactor.core.publisher.Mono;

/**
 * The errors the kit does not know: the platform's 429 passes through with its Retry-After, any
 * other platform failure is a 502; the state service's 404 and 409 pass through, anything else
 * from it is a 502; a webhook whose signature does not verify is a 401.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class DriftWebErrors {

    @ExceptionHandler(PlatformException.class)
    public Mono<ResponseEntity<Errors.Body>> platform(PlatformException ex) {
        if (ex.isTooManyRequests()) {
            int retry = ex.retryAfterSeconds() == null ? 5 : ex.retryAfterSeconds();
            return Mono.just(ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, String.valueOf(retry))
                    .body(Errors.tooManyRequests("platform.rateLimited: the platform asks to retry later", retry)));
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("status", ex.status());
        if (ex.errorText() != null) {
            detail.put("error", ex.errorText());
        }
        if (ex.problems() != null && !ex.problems().isEmpty()) {
            detail.put("problems", ex.problems());
        }
        return Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new Errors.Body("platform.failed: " + ex.errorText(), null, ex.correlationId(), null, detail)));
    }

    @ExceptionHandler(StateServicePort.StateError.class)
    public Mono<ResponseEntity<Errors.Body>> state(StateServicePort.StateError ex) {
        if (ex.notFound() || ex.status() == HttpStatus.GONE.value()) {
            return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).body(Errors.of("state: " + ex.error())));
        }
        if (ex.conflict()) {
            return Mono.just(ResponseEntity.status(HttpStatus.CONFLICT).body(Errors.conflict("state: " + ex.error(), ex.detail())));
        }
        return Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new Errors.Body("state.failed: " + ex.error(), null, null, null,
                Map.of("status", ex.status()))));
    }

    @ExceptionHandler(WorkflowStarterPort.NoCredential.class)
    public Mono<ResponseEntity<Errors.Body>> noCredential(WorkflowStarterPort.NoCredential ex) {
        return Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Errors.of("platform.noCredential: " + ex.getMessage())));
    }

    @ExceptionHandler(WebhookService.BadSignature.class)
    public Mono<ResponseEntity<Errors.Body>> badSignature(WebhookService.BadSignature ex) {
        return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Errors.of("signature: " + ex.getMessage())));
    }
}
