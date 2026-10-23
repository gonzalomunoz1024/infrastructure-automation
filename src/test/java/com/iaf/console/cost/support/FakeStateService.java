package com.iaf.console.cost.support;

import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.iaf.console.cost.ledger.domain.ResourceSnapshot;
import com.iaf.console.cost.ledger.ports.outbound.StateServicePort;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The state service as a map the tests write. */
public final class FakeStateService implements StateServicePort {

    private final Map<String, ResourceSnapshot> resources = new ConcurrentHashMap<>();

    public void put(ResourceSnapshot r) {
        resources.put(r.wfrn(), r);
    }

    @Override
    public Mono<ResourceSnapshot> find(AccountScope scope, String wfrn) {
        return Mono.justOrEmpty(resources.get(wfrn)).filter(r -> r.accountId().equals(scope.accountId()));
    }

    @Override
    public Flux<ResourceSnapshot> list(AccountScope scope, Instant updatedAfter, boolean deleted, String type) {
        return Flux.fromStream(() -> resources.values().stream().filter(r -> r.accountId().equals(scope.accountId()))
                .filter(r -> deleted == "deleted".equals(r.state()))
                .filter(r -> type == null || type.equals(r.type()))
                .filter(r -> updatedAfter == null || r.changedAt().isAfter(updatedAfter))
                .sorted(Comparator.comparing(ResourceSnapshot::changedAt)));
    }
}
