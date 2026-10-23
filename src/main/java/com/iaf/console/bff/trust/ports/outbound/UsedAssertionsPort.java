package com.iaf.console.bff.trust.ports.outbound;

import java.time.Instant;

import reactor.core.publisher.Mono;

/**
 * The client assertions already redeemed at {@code POST /internal/v1/token}, kept until each would
 * have expired anyway: memory for one instance, Mongo shared by every replica (the {@code mongo}
 * profile), so an assertion is redeemed once across the deployment, not once per replica.
 */
public interface UsedAssertionsPort {

    /** True the first time {@code key} is presented; false on every later try until {@code forgetAfter}. */
    Mono<Boolean> firstUse(String key, Instant forgetAfter);
}
