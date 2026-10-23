package com.iaf.console.cost.chargeback.ports.outbound;

import java.time.Instant;

import com.iaf.console.cost.chargeback.domain.ChargebackMonth;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The published months, final once stored. */
public interface ChargebackStorePort {

    Mono<ChargebackMonth> find(AccountScope scope, String month);

    /** Stores the month unless it is stored; answers the stored one either way, so a month is published once. */
    Mono<ChargebackMonth> insertIfAbsent(ChargebackMonth month);

    /** The published months finance's export has not taken yet. */
    @GlobalRead("the closer's own retry walk over every account's undelivered months")
    Flux<ChargebackMonth> listUndelivered(int limit);

    Mono<Void> markDelivered(String accountId, String month, Instant at);
}
