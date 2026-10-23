package com.iaf.console.cost.chargeback.adapters.memory;

import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.iaf.console.cost.chargeback.domain.ChargebackMonth;
import com.iaf.console.cost.chargeback.ports.outbound.ChargebackStorePort;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The published months of one JVM. */
public final class InMemoryChargebacks implements ChargebackStorePort {

    private final Map<String, ChargebackMonth> months = new ConcurrentHashMap<>();

    @Override
    public Mono<ChargebackMonth> find(AccountScope scope, String month) {
        return Mono.justOrEmpty(months.get(scope.accountId() + ":" + month));
    }

    @Override
    public Mono<ChargebackMonth> insertIfAbsent(ChargebackMonth month) {
        return Mono.fromSupplier(() -> months.computeIfAbsent(month.id(), k -> month));
    }

    @Override
    public Flux<ChargebackMonth> listUndelivered(int limit) {
        return Flux.fromStream(() -> months.values().stream().filter(m -> m.deliveredAt() == null).sorted(Comparator.comparing(ChargebackMonth::id))
                .limit(limit));
    }

    @Override
    public Mono<Void> markDelivered(String accountId, String month, Instant at) {
        return Mono.fromRunnable(() -> months.computeIfPresent(accountId + ":" + month, (k, m) -> m.deliveredAt() == null ? m.delivered(at) : m));
    }
}
