package com.iaf.console.bff.drift.adapters.inbound;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;

import com.iaf.console.bff.drift.domain.DriftGatewayException;
import com.iaf.console.kit.correlation.Correlation;
import com.iaf.console.kit.errors.Errors;

import reactor.core.publisher.Mono;

/**
 * The drift service's 502, passed on as it came: the platform (or the state service) refused the
 * drift service's call, and the web app shows the correlation id the drift service answered, the
 * platform's, so the run can be found where it failed. Ahead of the BFF's own mapping, which would
 * answer a generic 502 with the BFF's correlation id instead; a 502 without one gets the BFF's.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE + 4)
public class DriftExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(DriftExceptionHandler.class);

    @ExceptionHandler(DriftGatewayException.class)
    public Mono<ResponseEntity<Errors.Body>> gateway(DriftGatewayException ex, ServerWebExchange exchange) {
        String correlationId = ex.correlationId() == null || ex.correlationId().isBlank() ? Correlation.of(exchange) : ex.correlationId();
        log.warn("drift service answered 502 [{}]: {}", correlationId, ex.getMessage());
        return Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new Errors.Body(ex.getMessage(), null, correlationId, null, ex.detail())));
    }
}
