package com.iaf.console.identity.elevations.adapters.memory;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

import com.iaf.console.identity.elevations.domain.Elevation;
import com.iaf.console.identity.elevations.ports.outbound.ElevationStore;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.paging.Cursor;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The in-memory elevations of the demo and the tests. */
public final class InMemoryElevationStore implements ElevationStore {

    private final Map<String, Elevation> elevations = new ConcurrentSkipListMap<>();

    @Override
    public Mono<Elevation> insert(Elevation e) {
        return Mono.fromCallable(() -> {
            if (elevations.putIfAbsent(e.elevationId(), e) != null) {
                throw new ConflictException("the elevation id exists");
            }
            return e;
        });
    }

    @Override
    public Mono<Elevation> update(Elevation e, long expectedVersion) {
        return Mono.fromCallable(() -> {
            synchronized (elevations) {
                Elevation current = elevations.get(e.elevationId());
                if (current == null || current.version() != expectedVersion) {
                    return null;
                }
                elevations.put(e.elevationId(), e);
                return e;
            }
        });
    }

    @Override
    public Mono<Elevation> find(AccountScope scope, String elevationId) {
        return Mono.justOrEmpty(elevations.get(elevationId)).filter(e -> e.accountId().equals(scope.accountId()));
    }

    @Override
    public Flux<Elevation> findOpen(AccountScope scope, String userId, String role, Elevation.Kind kind) {
        return Flux.fromStream(() -> elevations.values().stream()
                .filter(e -> e.accountId().equals(scope.accountId()) && e.user().userId().equals(userId) && stillOpen(e))
                .filter(e -> role == null || e.role().equals(role))
                .filter(e -> kind == null || e.kind() == kind));
    }

    @Override
    public Flux<Elevation> findAllOpen(AccountScope scope) {
        return Flux.fromStream(() -> elevations.values().stream().filter(e -> e.accountId().equals(scope.accountId()) && stillOpen(e)));
    }

    @Override
    public Mono<Page<Elevation>> list(AccountScope scope, String userId, Elevation.Status status, Elevation.Kind kind, int limit, String after) {
        return Mono.fromSupplier(() -> {
            String beforeId = after == null ? null : CursorPaging.decode(after).id();
            List<Elevation> fetched = elevations.values().stream()
                    .filter(e -> e.accountId().equals(scope.accountId()))
                    .filter(e -> userId == null || e.user().userId().equals(userId))
                    .filter(e -> status == null || e.status() == status)
                    .filter(e -> kind == null || e.kind() == kind)
                    .filter(e -> beforeId == null || e.elevationId().compareTo(beforeId) < 0)
                    .sorted(Comparator.comparing(Elevation::elevationId).reversed())
                    .limit(limit + 1L)
                    .toList();
            return Page.from(fetched, limit, e -> new Cursor(e.elevationId(), e.elevationId()));
        });
    }

    @Override
    public Flux<Elevation> findActive() {
        return Flux.fromStream(() -> elevations.values().stream().filter(e -> e.status() == Elevation.Status.active));
    }

    @Override
    public Flux<Elevation> findActiveByUser(String userId) {
        return Flux.fromStream(() -> elevations.values().stream().filter(e -> e.status() == Elevation.Status.active && e.user().userId().equals(userId)));
    }

    @Override
    public Flux<Elevation> findDue(Instant now, Instant askedBefore) {
        return Flux.fromStream(() -> elevations.values().stream()
                .filter(e -> e.status() == Elevation.Status.active && !now.isBefore(e.until())
                        || e.status() == Elevation.Status.pending && e.requestedAt().isBefore(askedBefore)));
    }

    private static boolean stillOpen(Elevation e) {
        return e.status() == Elevation.Status.pending || e.status() == Elevation.Status.active;
    }
}
