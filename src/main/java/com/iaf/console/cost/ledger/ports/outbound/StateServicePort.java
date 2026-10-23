package com.iaf.console.cost.ledger.ports.outbound;

import java.time.Instant;

import com.iaf.console.cost.ledger.domain.ResourceSnapshot;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The state service, the owner of every resource: the resource behind an event, and the account's lists for a sync. */
public interface StateServicePort {

    /** The resource by wfrn: empty when there is none, a tombstone when it answers 410. */
    Mono<ResourceSnapshot> find(AccountScope scope, String wfrn);

    /** The account's resources changed after an instant (all when null), oldest change first; {@code deleted} for the tombstones. */
    Flux<ResourceSnapshot> list(AccountScope scope, Instant updatedAfter, boolean deleted, String type);
}
