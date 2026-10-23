package com.iaf.console.state.inventory.ports.outbound;

import java.time.Duration;
import java.time.Instant;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Mono;

/** The answers given to {@code Idempotency-Key}s, kept for 24 hours so a retry gets the first result. */
@GlobalRead("keys are stored under the account that sent them; the id carries the account")
public interface IdempotencyKeyPort {

    /** What a key answered: the hash of the body it came with, the status and the body it got. */
    record StoredResponse(String id, String bodyHash, int status, ObjectNode body, Instant at) {
    }

    Mono<StoredResponse> find(String id);

    /** Stores the response unless the key is there; answers the stored one either way. */
    Mono<StoredResponse> save(StoredResponse response, Duration ttl);
}
