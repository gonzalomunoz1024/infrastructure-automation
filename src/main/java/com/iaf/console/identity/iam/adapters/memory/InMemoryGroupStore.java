package com.iaf.console.identity.iam.adapters.memory;

import java.util.List;

import com.iaf.console.identity.common.store.InMemoryNamedStore;
import com.iaf.console.identity.common.store.Keyed;
import com.iaf.console.identity.iam.domain.Group;
import com.iaf.console.identity.iam.ports.outbound.GroupStore;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The in-memory groups of the demo and the tests. */
public final class InMemoryGroupStore implements GroupStore {

    private final InMemoryNamedStore<Group> store = new InMemoryNamedStore<>(new Keyed<>() {
        @Override
        public String id(Group g) {
            return g.groupId();
        }

        @Override
        public String accountId(Group g) {
            return g.accountId();
        }

        @Override
        public String name(Group g) {
            return g.name();
        }

        @Override
        public long version(Group g) {
            return g.version();
        }
    }, "group");

    @Override
    public Mono<Group> insert(Group group) {
        return store.insert(group);
    }

    @Override
    public Mono<Group> update(Group group, long expectedVersion) {
        return store.update(group, expectedVersion);
    }

    @Override
    public Mono<Boolean> delete(AccountScope scope, String groupId) {
        return store.delete(scope, groupId);
    }

    @Override
    public Mono<Group> find(AccountScope scope, String groupId) {
        return store.find(scope, groupId);
    }

    @Override
    public Mono<Group> findByName(AccountScope scope, String name) {
        return store.findByName(scope, name);
    }

    @Override
    public Mono<Page<Group>> list(AccountScope scope, int limit, String after) {
        return store.list(scope, limit, after, null);
    }

    @Override
    public Flux<Group> findAll(AccountScope scope) {
        return store.findAll(scope);
    }

    @Override
    public Flux<Group> findEverything() {
        return store.findEverything();
    }

    @Override
    public Flux<Group> findByIdpGroups(List<String> idpGroups) {
        return store.findWhere(g -> g.idpGroups().stream().anyMatch(idpGroups::contains));
    }
}
