package com.iaf.console.identity.iam.adapters.mongo;

import java.util.List;

import com.iaf.console.identity.common.store.Keyed;
import com.iaf.console.identity.common.store.MongoNamedStore;
import com.iaf.console.identity.iam.domain.Role;
import com.iaf.console.identity.iam.ports.outbound.RoleStore;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Collection {@code roles}. */
public final class MongoRoleStore implements RoleStore, MongoIndexed {

    private static final Keyed<Role> KEYS = new Keyed<>() {
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
    };

    private final MongoNamedStore<Role> store;

    public MongoRoleStore(Mongo mongo) {
        this.store = new MongoNamedStore<>(mongo, "roles", Role.class, KEYS, "role");
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return store.ensureIndexes(List.of());
    }

    @Override
    public Mono<Role> insert(Role role) {
        return store.insert(role, null);
    }

    @Override
    public Mono<Role> update(Role role, long expectedVersion) {
        return store.update(role, expectedVersion, null);
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
