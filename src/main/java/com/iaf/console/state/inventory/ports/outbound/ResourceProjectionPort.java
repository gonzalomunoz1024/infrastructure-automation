package com.iaf.console.state.inventory.ports.outbound;

import java.time.Instant;
import java.util.Collection;

import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.kit.scope.GlobalRead;
import com.iaf.console.state.inventory.domain.Resource;
import com.iaf.console.state.inventory.domain.ResourceQuery;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The projection: the current resource of every wfrn, the fold of its log, written after each
 * append and rebuilt from the log when it lags. A write only wins when it carries a newer version,
 * so a late write of an older fold is dropped.
 */
public interface ResourceProjectionPort {

    /** Stores the resource unless a newer version is stored; answers whether it was written. */
    Mono<Boolean> upsert(Resource resource);

    /** The resource of a wfrn in the scope, a tombstone included; empty when unknown or another account's. */
    Mono<Resource> find(AccountScope scope, String wfrn);

    /** One page of the scope's resources. */
    Mono<Page<Resource>> list(AccountScope scope, ResourceQuery query);

    /**
     * The scope's resources that existed at an instant (created by then and not deleted before it),
     * of a type when one is given, in wfrn order after {@code afterWfrn}, at most {@code limit + 1}:
     * the candidates of an account's time travel, whose state then comes from their logs.
     */
    Flux<Resource> listExistingAt(AccountScope scope, String type, Instant at, String afterWfrn, int limit);

    /** The live resource a platform registry id is bound to, for the collision guard. */
    @GlobalRead("the guard checks the binding across accounts: a registry id belongs to one resource in the world")
    Mono<Resource> findByRegistryId(String registryId);

    /**
     * The resources bound to registry ids, tombstones included, for the relationship reads; when an id
     * names a live resource and a tombstone, both are answered and the caller prefers the live one.
     */
    @GlobalRead("relationships resolve the registry's ids in every account; the reads show a caller only its own account's resources")
    Flux<Resource> findByRegistryIds(Collection<String> registryIds);

    /** The live resources built from any of the registry ids, at most {@code limit}, for lineage, blast radius and the map. */
    @GlobalRead("a blast radius counts dependents in every account; the reads show a caller only its own account's resources")
    Flux<Resource> findDependents(Collection<String> registryIds, int limit);

    /** Pending resources requested before an instant, oldest first, for the reaper. */
    @GlobalRead("the reaper sweeps every account")
    Flux<Resource> listPendingBefore(Instant cutoff, int limit);

    /** Updating or deleting resources whose last change is before an instant, for the reaper. */
    @GlobalRead("the reaper sweeps every account")
    Flux<Resource> listBusyBefore(Instant cutoff, int limit);

    /** Resources moved to another account between two instants, oldest first: the reaper finishes a move cut short. */
    @GlobalRead("the reaper's sweep of this service's own transfers")
    Flux<Resource> listTransferredBetween(Instant from, Instant to, int limit);

    /** How many live resources the projection holds. */
    @GlobalRead("a count of this service's own projection")
    Mono<Long> countLive();
}
