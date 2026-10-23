package com.iaf.console.cost.ledger.ports.outbound;

import com.iaf.console.cost.ledger.domain.RateRecord;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** One record per resource: what it adds to the ledger now, saved by compare-and-set on its revision. */
public interface RateStorePort {

    Mono<RateRecord> find(AccountScope scope, String wfrn);

    /**
     * Stores the record when the stored one is at {@code expectedRev} (0: none stored yet); false
     * when another writer got there first.
     */
    Mono<Boolean> save(RateRecord record, long expectedRev);

    /** Records whose deltas are not known to be stored yet: a crash between the two writes. */
    @GlobalRead("the ledger's own repair walk over every account")
    Flux<RateRecord> listPending(int limit);

    /** Clears the pending deltas of a record still at {@code rev}. */
    Mono<Void> markFlushed(String wfrn, long rev);

    /** How many records there are and how many of them are charged but unpriced, for the status. */
    @GlobalRead("counts over the whole ledger, no record")
    Mono<long[]> countAll();
}
