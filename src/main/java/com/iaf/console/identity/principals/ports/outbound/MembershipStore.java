package com.iaf.console.identity.principals.ports.outbound;

import com.iaf.console.identity.principals.domain.Membership;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Where memberships live: one per user and account. */
public interface MembershipStore {

    Mono<Membership> insert(Membership membership);

    Mono<Membership> update(Membership membership, long expectedVersion);

    Mono<Boolean> delete(AccountScope scope, String userId);

    Mono<Membership> find(AccountScope scope, String userId);

    /** One page, by user id, with an optional email or name prefix. */
    Mono<Page<Membership>> list(AccountScope scope, String query, int limit, String after);

    Flux<Membership> findAll(AccountScope scope);

    Flux<Membership> findByGroup(AccountScope scope, String groupId);

    Mono<Long> countByGroup(AccountScope scope, String groupId);

    /** Every membership of a user, across accounts. */
    @GlobalRead
    Flux<Membership> findByUser(String userId);

    /** Every membership of every account, for the compiler. */
    @GlobalRead
    Flux<Membership> findEverything();

    /** Copies a user's new email or name onto every membership; the versions stay. */
    Mono<Void> updateUserFields(String userId, String email, String name);
}
