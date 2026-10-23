package com.iaf.console.state.inventory.adapters.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.paging.Cursor;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.state.inventory.domain.ActivityQuery;
import com.iaf.console.state.inventory.domain.ResourceMatching;
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
    public Flux<ResourceEvent> findActivity(AccountScope scope, ActivityQuery q) {
        return Flux.defer(() -> {
            List<ResourceEvent> matching = new ArrayList<>();
            for (ResourceEvent e : all()) {
                if (scope.covers(e.accountId(), e.region()) && within(e, q.from(), q.to()) && (q.type() == null || q.type().equals(e.type().wire()))
                        && (q.actor() == null || q.actor().equals(e.actor())) && (q.jobId() == null || q.jobId().equals(e.jobId()))
                        && (q.wfrnPattern() == null || ResourceMatching.regexOf(q.wfrnPattern()).matcher(e.wfrn()).matches())
                        && (q.after() == null || before(e, q.after()))) {
                    matching.add(e);
                }
            }
            matching.sort(TIME_ORDER.reversed());
            return Flux.fromIterable(matching.subList(0, Math.min(matching.size(), q.limit() + 1)));
        });
    }

    @Override
    public Flux<ResourceEvent> findBetween(AccountScope scope, Instant from, Instant to, Cursor after, int limit) {
        return Flux.defer(() -> {
            List<ResourceEvent> matching = new ArrayList<>();
            for (ResourceEvent e : all()) {
                if (scope.covers(e.accountId(), e.region()) && within(e, from, to) && (after == null || beyond(e, after))) {
                    matching.add(e);
                }
            }
            matching.sort(TIME_ORDER);
            return Flux.fromIterable(matching.subList(0, Math.min(matching.size(), Math.max(0, limit))));
        });
    }

    @Override
    public Flux<AccountRegion> findAccountsWithEvents(Instant from, Instant to) {
        return Flux.defer(() -> {
            Set<AccountRegion> found = new LinkedHashSet<>();
            all().stream().filter(e -> within(e, from, to)).sorted(TIME_ORDER).forEach(e -> found.add(new AccountRegion(e.accountId(), e.region())));
            return Flux.fromIterable(found);
        });
    }

    private List<ResourceEvent> all() {
        List<ResourceEvent> events = new ArrayList<>();
        logs.values().forEach(log -> events.addAll(log.values()));
        return events;
    }

    private static boolean within(ResourceEvent e, Instant from, Instant to) {
        return (from == null || !e.at().isBefore(from)) && (to == null || e.at().isBefore(to));
    }

    private static boolean before(ResourceEvent e, Cursor c) {
        Instant at = com.iaf.console.kit.mongo.Mongo.fromNanos(c.sortLong());
        return e.at().isBefore(at) || (e.at().equals(at) && ActivityQuery.idOf(e).compareTo(c.id()) < 0);
    }

    private static boolean beyond(ResourceEvent e, Cursor c) {
        Instant at = com.iaf.console.kit.mongo.Mongo.fromNanos(c.sortLong());
        return e.at().isAfter(at) || (e.at().equals(at) && ActivityQuery.idOf(e).compareTo(c.id()) > 0);
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
