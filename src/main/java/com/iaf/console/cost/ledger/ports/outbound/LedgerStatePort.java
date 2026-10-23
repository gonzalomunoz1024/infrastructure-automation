package com.iaf.console.cost.ledger.ports.outbound;

import java.time.LocalDate;

import com.iaf.console.cost.ledger.domain.AccountLedger;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Where each account's ledger stands. */
public interface LedgerStatePort {

    Mono<AccountLedger> find(AccountScope scope);

    /** Every account the ledger holds, for the closer. */
    @GlobalRead("the closer walks every account's position, no resource data")
    Flux<AccountLedger> listAll();

    /** Creates the account's ledger unless it exists; answers the stored one. */
    Mono<AccountLedger> createIfAbsent(AccountLedger ledger);

    /** Marks a day reopened by a late delta: {@code dirtyFrom} becomes the earlier day, {@code rev} grows. */
    Mono<Void> markDirty(String accountId, LocalDate day);

    /** Moves {@code closedThrough} and clears {@code dirtyFrom} when the ledger is still at {@code expectedRev}. */
    Mono<Boolean> closed(String accountId, LocalDate closedThrough, long expectedRev);
}
