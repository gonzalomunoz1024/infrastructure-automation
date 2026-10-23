package com.iaf.console.identity.iam.adapters.memory;

import com.iaf.console.identity.common.store.InMemoryNamedStore;
import com.iaf.console.identity.common.store.Keyed;
import com.iaf.console.identity.iam.domain.Policy;
import com.iaf.console.identity.iam.ports.outbound.PolicyStore;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The in-memory policies of the demo and the tests. */
public final class InMemoryPolicyStore implements PolicyStore {

    private final InMemoryNamedStore<Policy> store = new InMemoryNamedStore<>(new Keyed<>() {
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
    }, "policy");

    @Override
    public Mono<Policy> insert(Policy policy) {
        return store.insert(policy);
    }

    @Override
    public Mono<Policy> update(Policy policy, long expectedVersion) {
        return store.update(policy, expectedVersion);
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
