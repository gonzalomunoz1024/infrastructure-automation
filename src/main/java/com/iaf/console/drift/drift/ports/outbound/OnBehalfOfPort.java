package com.iaf.console.drift.drift.ports.outbound;

import java.util.List;

import reactor.core.publisher.Mono;

/**
 * Signs the {@code X-On-Behalf-Of} assertion (P-02) a revert job carries, naming the person who
 * asked for it. In production the BFF signs it and relays it with the call, and no local signer
 * exists (the platform trusts the BFF's key alone); the demo signs with the embedded fake's key.
 * Empty when this service cannot sign.
 */
public interface OnBehalfOfPort {

    /** The person a job is started for. */
    record Person(String subject, String name, String email, List<String> groups) {
        public Person {
            groups = groups == null ? List.of() : List.copyOf(groups);
        }
    }

    Mono<String> assertion(String accountId, String region, Person person);

    /** No local signer: a revert needs the assertion the BFF relays. */
    OnBehalfOfPort NONE = (accountId, region, person) -> Mono.empty();
}
