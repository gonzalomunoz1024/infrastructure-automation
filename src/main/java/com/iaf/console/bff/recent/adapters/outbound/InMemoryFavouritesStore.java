package com.iaf.console.bff.recent.adapters.outbound;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.console.bff.recent.domain.Favourite;
import com.iaf.console.bff.recent.domain.Favourites;
import com.iaf.console.bff.recent.ports.outbound.FavouritesStore;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/** Favourites of one instance, in memory. */
@Component
@Profile("!mongo")
public class InMemoryFavouritesStore implements FavouritesStore {

    private final Map<String, List<Favourite>> byPerson = new ConcurrentHashMap<>();

    @Override
    public Mono<List<Favourite>> list(AccountScope scope) {
        return Mono.fromSupplier(() -> byPerson.getOrDefault(key(scope), List.of()).stream().sorted(Favourites.NEWEST_FIRST).toList());
    }

    @Override
    public Mono<Favourites.Put> put(AccountScope scope, Favourite favourite, int max) {
        return Mono.fromSupplier(() -> {
            AtomicReference<Favourites.Put> outcome = new AtomicReference<>();
            byPerson.compute(key(scope), (k, current) -> {
                Favourites.Put put = Favourites.put(current == null ? List.of() : current, favourite, max);
                outcome.set(put);
                return put.favourites();
            });
            return outcome.get();
        });
    }

    @Override
    public Mono<Boolean> remove(AccountScope scope, String wfrn) {
        return Mono.fromSupplier(() -> {
            AtomicReference<Boolean> removed = new AtomicReference<>(false);
            byPerson.computeIfPresent(key(scope), (k, current) -> {
                List<Favourite> kept = current.stream().filter(f -> !f.wfrn().equals(wfrn)).toList();
                removed.set(kept.size() < current.size());
                return kept;
            });
            return removed.get();
        });
    }

    private static String key(AccountScope scope) {
        return scope.accountId() + ":" + scope.principal();
    }
}
