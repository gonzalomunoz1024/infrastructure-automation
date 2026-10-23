package com.iaf.console.identity.common.web;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;

import com.iaf.console.identity.common.auth.UnauthenticatedException;
import com.iaf.console.kit.correlation.Correlation;
import com.iaf.console.kit.errors.Errors;

import reactor.core.publisher.Mono;

/** The errors this service adds to the kit's: a denial (403 with its block) and a missing or bad token (401). */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WebErrorAdvice {

    @ExceptionHandler(DeniedException.class)
    public Mono<ResponseEntity<DeniedBody>> denied(DeniedException ex, ServerWebExchange exchange) {
        return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new DeniedBody(ex.getMessage(), ex.denial(), Correlation.of(exchange))));
    }

    @ExceptionHandler(UnauthenticatedException.class)
    public Mono<ResponseEntity<Errors.Body>> unauthenticated(UnauthenticatedException ex, ServerWebExchange exchange) {
        return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header("WWW-Authenticate", "Bearer realm=\"console\"")
                .body(Errors.of(ex.getMessage()).withCorrelationId(Correlation.of(exchange))));
    }
}
