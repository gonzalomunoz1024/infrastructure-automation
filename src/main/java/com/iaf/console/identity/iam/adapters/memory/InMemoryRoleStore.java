package com.iaf.console.identity.iam.adapters.memory;

import com.iaf.console.identity.common.store.InMemoryNamedStore;
import com.iaf.console.identity.common.store.Keyed;
import com.iaf.console.identity.iam.domain.Role;
import com.iaf.console.identity.iam.ports.outbound.RoleStore;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The in-memory roles of the demo and the tests. */
public final class InMemoryRoleStore implements RoleStore {

    private final InMemoryNamedStore<Role> store = new InMemoryNamedStore<>(new Keyed<>() {
        @Override
        public String id(Role r) {
            return r.roleId();
        }

        @Override
        public String accountId(Role r) {
            return r.accountId();
        }

        @Override
        public String name(Role r) {
            return r.name();
        }

        @Override
        public long version(Role r) {
            return r.version();
        }
    }, "role");

    @Override
    public Mono<Role> insert(Role role) {
        return store.insert(role);
    }

    @Override
    public Mono<Role> update(Role role, long expectedVersion) {
        return store.update(role, expectedVersion);
    }

    @Override
    public Mono<Boolean> delete(AccountScope scope, String roleId) {
        return store.delete(scope, roleId);
    }

    @Override
    public Mono<Role> find(AccountScope scope, String roleId) {
        return store.find(scope, roleId);
    }

    @Override
    public Mono<Role> findByName(AccountScope scope, String name) {
        return store.findByName(scope, name);
    }

    @Override
    public Mono<Page<Role>> list(AccountScope scope, int limit, String after) {
        return store.list(scope, limit, after, null);
    }

    @Override
    public Flux<Role> findAll(AccountScope scope) {
        return store.findAll(scope);
    }

    @Override
    public Flux<Role> findEverything() {
        return store.findEverything();
    }
}
