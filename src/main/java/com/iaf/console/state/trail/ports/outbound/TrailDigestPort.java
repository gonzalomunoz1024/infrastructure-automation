package com.iaf.console.state.trail.ports.outbound;

import java.time.Instant;
import java.util.Map;

import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.kit.scope.GlobalRead;
import com.iaf.console.state.trail.domain.Digest;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The trail's digests, the digester's progress and the public halves of the keys that signed them. */
public interface TrailDigestPort {

    /** Stores a digest unless one with its id is stored; answers whether it was written. */
    Mono<Boolean> save(Digest digest);

    /** The account's latest digest that ends at or before an instant. */
    Mono<Digest> findLatestBefore(AccountScope scope, Instant at);

    /** The account's digests whose period overlaps a window, oldest first. */
    Flux<Digest> listDigests(AccountScope scope, Instant from, Instant to);

    /** Where the digester has sealed every account up to; empty before its first pass. */
    @GlobalRead("the digester's own progress")
    Mono<Instant> findSealedThrough();

    /** Moves the digester's progress from what it read ({@code null} before the first pass) to a later instant; false when another process moved it first. */
    Mono<Boolean> advanceSealedThrough(Instant expected, Instant next);

    /** Keeps the public half of a signing key, so digests verify after the process that signed them is gone. */
    Mono<Void> saveKey(Map<String, Object> publicJwk);

    @GlobalRead("the trail's public keys are this service's own")
    Flux<Map<String, Object>> listKeys();
}
