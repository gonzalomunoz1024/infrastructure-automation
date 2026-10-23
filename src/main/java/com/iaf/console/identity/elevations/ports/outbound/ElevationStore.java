package com.iaf.console.identity.elevations.ports.outbound;

import java.time.Instant;

import com.iaf.console.identity.elevations.domain.Elevation;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Where elevations and break-glass access live. */
public interface ElevationStore {

    Mono<Elevation> insert(Elevation elevation);

    Mono<Elevation> update(Elevation elevation, long expectedVersion);

    Mono<Elevation> find(AccountScope scope, String elevationId);

    /** The stored-pending or stored-active ones of a person in an account; of one role and kind when given. */
    Flux<Elevation> findOpen(AccountScope scope, String userId, String role, Elevation.Kind kind);

    /** Every stored-pending or stored-active one of an account: what closing it ends. */
    Flux<Elevation> findAllOpen(AccountScope scope);

    /** One page, the newest first; of one person, status and kind when given. */
    Mono<Page<Elevation>> list(AccountScope scope, String userId, Elevation.Status status, Elevation.Kind kind, int limit, String after);

    /** Every stored-active one across accounts: what the authorization data compiles into grants. */
    @GlobalRead
    Flux<Elevation> findActive();

    /** A person's stored-active ones across accounts: what sign-in lists. */
    @GlobalRead
    Flux<Elevation> findActiveByUser(String userId);

    /** Active ones whose {@code until} has passed and pending ones asked before {@code askedBefore}: what the expiry sweep stores expired. */
    @GlobalRead
    Flux<Elevation> findDue(Instant now, Instant askedBefore);
}
