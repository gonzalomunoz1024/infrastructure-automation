package com.iaf.console.state.inventory.adapters.memory;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.state.inventory.application.InventoryService;
import com.iaf.console.state.inventory.domain.ResourceEvent;
import com.iaf.console.state.inventory.ports.outbound.ResourceEventStorePort;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The log of the demo profile and the tests: in memory, nothing survives a restart. */
public final class InMemoryResourceEventStore implements ResourceEventStorePort {

    private final Map<String, ConcurrentSkipListMap<Long, ResourceEvent>> logs = new ConcurrentHashMap<>();
    private final Map<String, Instant> expiry = new ConcurrentHashMap<>();

    @Override
    public Mono<ResourceEvent> append(ResourceEvent event) {
        return Mono.fromSupplier(() -> {
            ConcurrentSkipListMap<Long, ResourceEvent> log = logs.computeIfAbsent(event.wfrn(), k -> new ConcurrentSkipListMap<>());
            ResourceEvent existing = log.putIfAbsent(event.version(), event);
            if (existing != null) {
                throw new ConflictException(InventoryService.VERSION_CONFLICT + ": version " + event.version() + " of " + event.wfrn()
                        + " is already written");
            }
            expiry.remove(event.wfrn());
            return event;
        });
    }

    @Override
    public Flux<ResourceEvent> findEvents(AccountScope scope, String wfrn, long afterVersion, int limit) {
        if (wfrn == null) {
            return Flux.empty();
        }
        ConcurrentSkipListMap<Long, ResourceEvent> log = logs.get(wfrn);
        if (log == null) {
            return Flux.empty();
        }
        return Flux.fromIterable(log.tailMap(afterVersion, false).values())
                .filter(e -> scope.covers(e.accountId(), e.region()))
                .take(Math.max(0, limit));
    }

    @Override
    public Mono<Void> expireAt(String wfrn, Instant at) {
        return Mono.fromRunnable(() -> expiry.put(wfrn, at));
    }

    @Override
    public Mono<Long> countAll() {
        return Mono.fromSupplier(() -> logs.values().stream().mapToLong(Map::size).sum());
    }

    /** Drops the logs whose tombstone has lapsed, as MongoDB's TTL would; for tests. */
    public int purge(Instant now) {
        int dropped = 0;
        for (Map.Entry<String, Instant> e : expiry.entrySet()) {
            if (!now.isBefore(e.getValue())) {
                logs.remove(e.getKey());
                expiry.remove(e.getKey());
                dropped++;
            }
        }
        return dropped;
    }
}
