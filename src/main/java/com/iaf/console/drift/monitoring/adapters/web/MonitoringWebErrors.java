package com.iaf.console.drift.monitoring.adapters.web;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.iaf.console.drift.monitoring.ports.outbound.MetricsPort;
import com.iaf.console.kit.errors.Errors;

import reactor.core.publisher.Mono;

/**
 * The one monitoring error the kit does not know: a metrics read the platform failed to answer, or
 * that no credential could make, is a 502, as any other platform failure of this service is.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE + 6)
public class MonitoringWebErrors {

    @ExceptionHandler(MetricsPort.Unavailable.class)
    public Mono<ResponseEntity<Errors.Body>> unavailable(MetricsPort.Unavailable ex) {
        return Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Errors.of("metrics.failed: " + ex.getMessage())));
    }
}
