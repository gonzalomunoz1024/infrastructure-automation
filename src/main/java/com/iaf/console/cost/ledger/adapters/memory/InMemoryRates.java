package com.iaf.console.cost.ledger.adapters.memory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.iaf.console.cost.ledger.domain.RateRecord;
import com.iaf.console.cost.ledger.ports.outbound.RateStorePort;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The rate records of one JVM. */
public final class InMemoryRates implements RateStorePort {

    private final Map<String, RateRecord> records = new ConcurrentHashMap<>();

    @Override
    public Mono<RateRecord> find(AccountScope scope, String wfrn) {
        return Mono.justOrEmpty(records.get(wfrn)).filter(r -> r.accountId().equals(scope.accountId()));
    }

    @Override
    public Mono<Boolean> save(RateRecord record, long expectedRev) {
        return Mono.fromSupplier(() -> {
            boolean[] saved = {false};
            records.compute(record.wfrn(), (k, stored) -> {
                long rev = stored == null ? 0 : stored.rev();
                if (rev != expectedRev) {
                    return stored;
                }
                saved[0] = true;
                return record;
            });
            return saved[0];
        });
    }

    @Override
    public Flux<RateRecord> listPending(int limit) {
        return Flux.fromStream(() -> records.values().stream().filter(r -> !r.pending().isEmpty()).limit(limit));
    }

    @Override
    public Mono<Void> markFlushed(String wfrn, long rev) {
        return Mono.fromRunnable(() -> records.computeIfPresent(wfrn, (k, r) -> r.rev() == rev ? r.flushed() : r));
    }

    @Override
    public Mono<long[]> countAll() {
        return Mono.fromSupplier(() -> {
            long charged = records.values().stream().filter(RateRecord::charged).count();
            long unpriced = records.values().stream().filter(r -> r.charged() && r.contribution().unpriced() > 0).count();
            return new long[] {charged, unpriced};
        });
    }
}
