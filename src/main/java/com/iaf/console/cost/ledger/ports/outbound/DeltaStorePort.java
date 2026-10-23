package com.iaf.console.cost.ledger.ports.outbound;

import java.time.LocalDate;
import java.util.List;

import com.iaf.console.cost.ledger.domain.RateDelta;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The deltas, append-only and keyed by their deterministic id, so writing one twice keeps one. */
public interface DeltaStorePort {

    Mono<Void> insertAll(List<RateDelta> deltas);

    /** The account's deltas on the days {@code [from, to)}. */
    Flux<RateDelta> listDays(AccountScope scope, LocalDate from, LocalDate to);
}
