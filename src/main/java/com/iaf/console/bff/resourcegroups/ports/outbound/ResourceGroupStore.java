package com.iaf.console.bff.resourcegroups.ports.outbound;

import java.util.List;

import com.iaf.console.bff.resourcegroups.domain.CreateKey;
import com.iaf.console.bff.resourcegroups.domain.CreatedGroup;
import com.iaf.console.bff.resourcegroups.domain.ResourceGroup;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/**
 * Where resource groups live, per account and region: every call is scoped, and a group of
 * another account or region is never found. A name is unique within its account and region and
 * never changes.
 */
public interface ResourceGroupStore {

    /** Every group of the scope's account and region, in no particular order. */
    Mono<List<ResourceGroup>> list(AccountScope scope);

    /** One group; empty when the scope's account and region hold none of that id. */
    Mono<ResourceGroup> get(AccountScope scope, String groupId);

    /** The latest group the scope's principal created with this Idempotency-Key, with the key; empty when none. */
    Mono<CreatedGroup> findCreated(AccountScope scope, String idempotencyKey);

    /**
     * Stores a new group with the key it was created with (null when none), or errors with
     * {@code GroupNameTakenException} when its account and region hold the name already: the check
     * and the write are one step.
     */
    Mono<ResourceGroup> create(AccountScope scope, ResourceGroup group, CreateKey key);

    /** Replaces a group that is still at {@code expectedVersion}; empty when it moved on or is gone. */
    Mono<ResourceGroup> update(AccountScope scope, ResourceGroup group, long expectedVersion);

    /** Removes a group; whether there was one. */
    Mono<Boolean> delete(AccountScope scope, String groupId);
}
