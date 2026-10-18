package com.iaf.platform.gateway.ports.outbound;

import java.time.Instant;
import java.util.Optional;

import com.iaf.platform.gateway.domain.KeyedRequest;
import com.iaf.platform.gateway.domain.StoredAnswer;

/** Where idempotency keys and their first answers live, per caller. */
public interface IdempotencyStore {

    /**
     * Atomically claims a key: when the caller holds no live entry for it, stores {@code inFlight}
     * and returns empty; otherwise returns the live entry and changes nothing.
     */
    Optional<KeyedRequest> claim(KeyedRequest inFlight, Instant now);

    /** Records the answer of a claimed key. */
    void complete(String caller, String key, StoredAnswer answer);

    /** Frees a claimed key whose request did not succeed. */
    void release(String caller, String key);
}
