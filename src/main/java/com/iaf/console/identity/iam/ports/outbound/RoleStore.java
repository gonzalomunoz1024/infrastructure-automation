package com.iaf.console.identity.iam.ports.outbound;

import com.iaf.console.identity.iam.domain.Role;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Where an account's roles live; names are unique per account. */
public interface RoleStore {

    Mono<Role> insert(Role role);

    Mono<Role> update(Role role, long expectedVersion);

    Mono<Boolean> delete(AccountScope scope, String roleId);

    Mono<Role> find(AccountScope scope, String roleId);

    Mono<Role> findByName(AccountScope scope, String name);

    Mono<Page<Role>> list(AccountScope scope, int limit, String after);

    Flux<Role> findAll(AccountScope scope);

    @GlobalRead
    Flux<Role> findEverything();
}
