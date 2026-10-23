package com.iaf.console.bff.clients.ports.outbound;

import java.time.Instant;

import com.iaf.console.bff.clients.domain.DeviceGrant;
import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Mono;

/**
 * Where device grants wait for the person's decision: memory for one instance, Mongo for many. A
 * grant is found by its user code or by its device code's hash, and kept until ten minutes past its
 * expiry ({@link DeviceGrant#purgeAt()}); a grant past that is never found.
 */
@GlobalRead("a device grant is found by its own codes before anyone is signed in, not by an account")
public interface DeviceGrantStore {

    /** Stores a new grant; false when a kept grant holds its user code already (the caller draws another). */
    Mono<Boolean> create(DeviceGrant grant);

    /** The grant of a canonical user code; empty when none is kept. */
    Mono<DeviceGrant> findByUserCode(String userCode, Instant now);

    /** The grant whose device code has this SHA-256; empty when none is kept. */
    Mono<DeviceGrant> findByDeviceCode(String deviceCodeHash, Instant now);

    /** How many grants were asked for from an address since a moment: the rate limit's count. */
    Mono<Long> countRequestedFrom(String ip, Instant since);

    /** Replaces a grant that is still at {@code expectedVersion}; empty when it changed meanwhile or is gone. */
    Mono<DeviceGrant> update(DeviceGrant grant, long expectedVersion);
}
