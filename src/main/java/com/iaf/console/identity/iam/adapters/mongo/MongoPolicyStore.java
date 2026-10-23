package com.iaf.console.identity.iam.adapters.mongo;

import java.util.List;

import com.iaf.console.identity.common.store.Keyed;
import com.iaf.console.identity.common.store.MongoNamedStore;
import com.iaf.console.identity.iam.domain.Policy;
import com.iaf.console.identity.iam.ports.outbound.PolicyStore;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Collection {@code policies}. */
public final class MongoPolicyStore implements PolicyStore, MongoIndexed {

    private static final Keyed<Policy> KEYS = new Keyed<>() {
        @Override
        public String id(Policy p) {
            return p.policyId();
        }

        @Override
        public String accountId(Policy p) {
            return p.accountId();
        }

        @Override
        public String name(Policy p) {
            return p.name();
        }

        @Override
        public long version(Policy p) {
            return p.version();
        }
    };

    private final MongoNamedStore<Policy> store;

    public MongoPolicyStore(Mongo mongo) {
        this.store = new MongoNamedStore<>(mongo, "policies", Policy.class, KEYS, "policy");
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return store.ensureIndexes(List.of());
    }

    @Override
    public Mono<Policy> insert(Policy policy) {
        return store.insert(policy, null);
    }

    @Override
    public Mono<Policy> update(Policy policy, long expectedVersion) {
        return store.update(policy, expectedVersion, null);
    }

    @Override
    public Mono<Boolean> delete(AccountScope scope, String policyId) {
        return store.delete(scope, policyId);
    }

    @Override
    public Mono<Policy> find(AccountScope scope, String policyId) {
        return store.find(scope, policyId);
    }

    @Override
    public Mono<Policy> findByName(AccountScope scope, String name) {
        return store.findByName(scope, name);
    }

    @Override
    public Mono<Page<Policy>> list(AccountScope scope, int limit, String after) {
        return store.list(scope, limit, after, null);
    }

    @Override
    public Flux<Policy> findAll(AccountScope scope) {
        return store.findAll(scope);
    }

    @Override
    public Flux<Policy> findEverything() {
        return store.findEverything();
    }
}
