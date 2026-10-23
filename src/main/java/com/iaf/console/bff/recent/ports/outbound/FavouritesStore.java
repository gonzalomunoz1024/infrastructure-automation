package com.iaf.console.bff.recent.ports.outbound;

import java.util.List;

import com.iaf.console.bff.recent.domain.Favourite;
import com.iaf.console.bff.recent.domain.Favourites;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/**
 * Where a person's favourite resources live (decision 32.3), per account and person: the scope's
 * account and its principal, the person's user id (the region is no part of the key).
 */
public interface FavouritesStore {

    /** The person's favourites in the account, newest first. */
    Mono<List<Favourite>> list(AccountScope scope);

    /**
     * Puts a favourite as {@link Favourites#put} says, at once: one already there keeps when it was
     * added; a new one is refused, {@code full}, when the person has {@code max} already.
     */
    Mono<Favourites.Put> put(AccountScope scope, Favourite favourite, int max);

    /** Removes a favourite; true when there was one. */
    Mono<Boolean> remove(AccountScope scope, String wfrn);
}
