package com.iaf.console.state.inventory.adapters.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Set;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.state.inventory.domain.Resource;
import com.iaf.console.state.inventory.domain.ResourceMatching;
import com.iaf.console.state.inventory.domain.ResourceQuery;
import com.iaf.console.state.inventory.domain.ResourceState;
import com.iaf.console.state.inventory.ports.outbound.ResourceProjectionPort;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The projection of the demo profile and the tests: in memory, filtered and sorted in Java by the domain's rules. */
public final class InMemoryResourceProjection implements ResourceProjectionPort {

    private final Map<String, Resource> resources = new ConcurrentHashMap<>();

    @Override
    public Mono<Boolean> upsert(Resource resource) {
        return Mono.fromSupplier(() -> {
            boolean[] written = new boolean[1];
            resources.compute(resource.wfrn(), (k, stored) -> {
                if (stored != null && stored.version() >= resource.version()) {
                    return stored;
                }
                written[0] = true;
                return resource;
            });
            return written[0];
        });
    }

    @Override
    public Mono<Resource> find(AccountScope scope, String wfrn) {
        return Mono.justOrEmpty(resources.get(wfrn)).filter(r -> scope.covers(r.accountId(), r.region()));
    }

    @Override
    public Mono<Page<Resource>> list(AccountScope scope, ResourceQuery query) {
        return Mono.fromSupplier(() -> {
            List<Resource> matching = new ArrayList<>();
            for (Resource r : resources.values()) {
                if (scope.covers(r.accountId(), r.region()) && ResourceMatching.matches(query, r) && ResourceMatching.after(query, r)) {
                    matching.add(r);
                }
            }
            matching.sort(ResourceMatching.order(query));
            List<Resource> fetched = matching.subList(0, Math.min(matching.size(), query.limit() + 1));
            return Page.from(new ArrayList<>(fetched), query.limit(), r -> ResourceMatching.cursorOf(query, r));
        });
    }

    @Override
    public Flux<Resource> listExistingAt(AccountScope scope, String type, Instant at, String afterWfrn, int limit) {
        return Flux.fromIterable(resources.values())
                .filter(r -> scope.covers(r.accountId(), r.region()) && (type == null || type.equals(r.type()))
                        && r.createdAt() != null && !r.createdAt().isAfter(at) && (r.live() || (r.deletedAt() != null && r.deletedAt().isAfter(at)))
                        && (afterWfrn == null || r.wfrn().compareTo(afterWfrn) > 0))
                .sort(Comparator.comparing(Resource::wfrn))
                .take(limit + 1L);
    }

    @Override
    public Mono<Resource> findByRegistryId(String registryId) {
        return Flux.fromIterable(resources.values()).filter(r -> r.live() && registryId.equals(r.registryId())).next();
    }

    @Override
    public Flux<Resource> findByRegistryIds(Collection<String> registryIds) {
        Set<String> ids = Set.copyOf(registryIds);
        return Flux.fromIterable(resources.values()).filter(r -> r.registryId() != null && ids.contains(r.registryId()));
    }

    @Override
    public Flux<Resource> findDependents(Collection<String> registryIds, int limit) {
        Set<String> ids = Set.copyOf(registryIds);
        return Flux.fromIterable(resources.values())
                .filter(r -> r.live() && r.builtFrom().stream().anyMatch(ids::contains))
                .sort(Comparator.comparing(Resource::wfrn))
                .take(limit);
    }

    @Override
    public Flux<Resource> listPendingBefore(Instant cutoff, int limit) {
        return Flux.fromIterable(resources.values())
                .filter(r -> r.state() == ResourceState.PENDING && r.updatedAt() != null && r.updatedAt().isBefore(cutoff))
                .sort(Comparator.comparing(Resource::updatedAt))
                .take(limit);
    }

    @Override
    public Flux<Resource> listBusyBefore(Instant cutoff, int limit) {
        return Flux.fromIterable(resources.values())
                .filter(r -> (r.state() == ResourceState.UPDATING || r.state() == ResourceState.DELETING) && r.updatedAt() != null
                        && r.updatedAt().isBefore(cutoff))
                .sort(Comparator.comparing(Resource::updatedAt))
                .take(limit);
    }

    @Override
    public Flux<Resource> listTransferredBetween(Instant from, Instant to, int limit) {
        return Flux.fromIterable(resources.values())
                .filter(r -> r.transferredTo() != null && r.updatedAt() != null && !r.updatedAt().isBefore(from) && r.updatedAt().isBefore(to))
                .sort(Comparator.comparing(Resource::updatedAt))
                .take(limit);
    }

    @Override
    public Mono<Long> countLive() {
        return Mono.fromSupplier(() -> resources.values().stream().filter(Resource::live).count());
    }
}
