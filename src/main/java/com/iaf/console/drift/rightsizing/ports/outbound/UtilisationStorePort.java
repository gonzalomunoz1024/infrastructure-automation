package com.iaf.console.drift.rightsizing.ports.outbound;

import java.time.Instant;

import com.iaf.console.drift.rightsizing.domain.ResourceUtilisation;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Mono;

/** What the utilisation pass keeps per resource: its last utilisation and size, so the next pass sees a change of size. */
public interface UtilisationStorePort {

    @GlobalRead("the utilisation pass works across accounts; the wfrn names the account")
    Mono<ResourceUtilisation> find(String wfrn);

    /** Keeps the resource's latest utilisation in place of the one before. */
    Mono<ResourceUtilisation> save(ResourceUtilisation utilisation);

    /** Forgets a resource that left the inventory. */
    Mono<Void> delete(String wfrn);

    /** When a pass last read one of the account's resources. */
    Mono<Instant> findLastReadAt(AccountScope scope);
}
