package com.iaf.console.drift.drift.adapters.memory;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.iaf.console.drift.drift.domain.Tier;
import com.iaf.console.drift.drift.domain.Watch;
import com.iaf.console.drift.drift.ports.outbound.WatchStorePort;
import com.iaf.console.kit.paging.Cursor;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The watch list in memory, for the demo and the tests. */
public final class InMemoryWatchStore implements WatchStorePort {

    private final Map<String, Watch> byWfrn = new ConcurrentHashMap<>();

    @Override
    public Mono<Watch> find(String wfrn) {
        return Mono.justOrEmpty(byWfrn.get(wfrn));
    }

    @Override
    public Flux<Watch> findByWfrns(Collection<String> wfrns) {
        return Flux.fromIterable(wfrns).mapNotNull(byWfrn::get);
    }

    @Override
    public Flux<Watch> findDue(int shard, Tier tier, Instant now, int limit) {
        return Flux.fromStream(() -> byWfrn.values().stream()
                .filter(w -> w.shard() == shard && (tier == null || w.tier() == tier) && (w.nextCheckAt() == null || !w.nextCheckAt().isAfter(now)))
                .sorted(Comparator.comparing((Watch w) -> w.nextCheckAt() == null ? Instant.EPOCH : w.nextCheckAt()).thenComparing(Watch::wfrn))
                .limit(limit));
    }

    @Override
    public Flux<Watch> findByProviderId(String providerId, String type) {
        return Flux.fromStream(() -> byWfrn.values().stream()
                .filter(w -> providerId.equals(w.providerId()) || providerId.equals(w.registryId()))
                .filter(w -> type == null || type.equals(w.type())));
    }

    @Override
    public Flux<String> listAccounts() {
        return Flux.fromStream(() -> byWfrn.values().stream().map(Watch::accountId).distinct().sorted());
    }

    @Override
    public Mono<Page<Watch>> list(AccountScope scope, String type, int limit, String after) {
        return Mono.fromSupplier(() -> {
            String from = after == null || after.isBlank() ? null : CursorPaging.decode(after).id();
            List<Watch> fetched = byWfrn.values().stream()
                    .filter(w -> w.accountId().equals(scope.accountId()) && (type == null || type.equals(w.type())))
                    .filter(w -> from == null || w.wfrn().compareTo(from) > 0)
                    .sorted(Comparator.comparing(Watch::wfrn))
                    .limit(limit + 1L)
                    .toList();
            return Page.from(fetched, limit, w -> new Cursor(w.wfrn(), w.wfrn()));
        });
    }

    @Override
    public Mono<Instant> findLastCheckedAt(AccountScope scope) {
        return Mono.justOrEmpty(byWfrn.values().stream()
                .filter(w -> w.accountId().equals(scope.accountId()))
                .map(Watch::lastCheckedAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder()));
    }

    @Override
    public Mono<Watch> save(Watch watch) {
        return Mono.fromCallable(() -> {
            Watch[] saved = new Watch[1];
            byWfrn.compute(watch.wfrn(), (wfrn, current) -> {
                long stored = current == null ? 0 : current.rev();
                if (stored != watch.rev()) {
                    return current;
                }
                saved[0] = watch.withRev(stored + 1);
                return saved[0];
            });
            if (saved[0] == null) {
                throw new Conflict(watch.wfrn());
            }
            return saved[0];
        });
    }

    @Override
    public Mono<Void> delete(String wfrn) {
        return Mono.fromRunnable(() -> byWfrn.remove(wfrn));
    }

    @Override
    public Mono<Long> count() {
        return Mono.fromSupplier(() -> (long) byWfrn.size());
    }
}
