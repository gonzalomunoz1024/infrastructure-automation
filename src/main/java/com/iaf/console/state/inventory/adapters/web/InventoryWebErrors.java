package com.iaf.console.state.inventory.adapters.web;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.iaf.console.state.inventory.domain.Tombstone;
import com.iaf.console.state.inventory.domain.TombstoneException;

import reactor.core.publisher.Mono;

/** The one error the kit does not know: a tombstone is a 410 with the contract's {@code Tombstone} body. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class InventoryWebErrors {

    @ExceptionHandler(TombstoneException.class)
    public Mono<ResponseEntity<Tombstone>> gone(TombstoneException ex) {
        return Mono.just(ResponseEntity.status(HttpStatus.GONE).body(ex.tombstone()));
    }
}
