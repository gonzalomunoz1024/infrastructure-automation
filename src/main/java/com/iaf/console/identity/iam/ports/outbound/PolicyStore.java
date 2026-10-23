package com.iaf.console.identity.iam.ports.outbound;

import com.iaf.console.identity.iam.domain.Policy;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Where an account's policies live; names are unique per account. */
public interface PolicyStore {

    Mono<Policy> insert(Policy policy);

    Mono<Policy> update(Policy policy, long expectedVersion);

    Mono<Boolean> delete(AccountScope scope, String policyId);

    Mono<Policy> find(AccountScope scope, String policyId);

    Mono<Policy> findByName(AccountScope scope, String name);

    Mono<Page<Policy>> list(AccountScope scope, int limit, String after);

    Flux<Policy> findAll(AccountScope scope);

    @GlobalRead
    Flux<Policy> findEverything();
}
