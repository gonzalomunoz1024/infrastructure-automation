package com.iaf.console.drift.rightsizing.adapters.memory;

import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.iaf.console.drift.rightsizing.domain.ResourceUtilisation;
import com.iaf.console.drift.rightsizing.ports.outbound.UtilisationStorePort;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/** Each resource's last utilisation in memory, for the demo and the tests. */
public final class InMemoryUtilisationStore implements UtilisationStorePort {

    private final Map<String, ResourceUtilisation> byWfrn = new ConcurrentHashMap<>();

    @Override
    public Mono<ResourceUtilisation> find(String wfrn) {
        return Mono.justOrEmpty(byWfrn.get(wfrn));
    }

    @Override
    public Mono<ResourceUtilisation> save(ResourceUtilisation utilisation) {
        return Mono.fromSupplier(() -> {
            byWfrn.put(utilisation.wfrn(), utilisation);
            return utilisation;
        });
    }

    @Override
    public Mono<Void> delete(String wfrn) {
        return Mono.fromRunnable(() -> byWfrn.remove(wfrn));
    }

    @Override
    public Mono<Instant> findLastReadAt(AccountScope scope) {
        return Mono.justOrEmpty(byWfrn.values().stream()
                .filter(u -> scope.accountId().equals(u.accountId()))
                .map(ResourceUtilisation::readAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder()));
    }
}
