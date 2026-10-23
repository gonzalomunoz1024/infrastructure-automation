package com.iaf.console.state.inventory.ports.outbound;

import java.time.Instant;

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

    /** The live resource a platform registry id is bound to, for the collision guard. */
    @GlobalRead("the guard checks the binding across accounts: a registry id belongs to one resource in the world")
    Mono<Resource> findByRegistryId(String registryId);

    /** Pending resources requested before an instant, oldest first, for the reaper. */
    @GlobalRead("the reaper sweeps every account")
    Flux<Resource> listPendingBefore(Instant cutoff, int limit);

    /** Updating or deleting resources whose last change is before an instant, for the reaper. */
    @GlobalRead("the reaper sweeps every account")
    Flux<Resource> listBusyBefore(Instant cutoff, int limit);

    /** How many live resources the projection holds. */
    @GlobalRead("a count of this service's own projection")
    Mono<Long> countLive();
}
