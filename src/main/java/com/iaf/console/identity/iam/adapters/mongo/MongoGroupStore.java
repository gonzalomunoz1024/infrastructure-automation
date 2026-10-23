package com.iaf.console.identity.iam.adapters.mongo;

import java.util.List;

import org.bson.Document;

import com.iaf.console.identity.common.store.Keyed;
import com.iaf.console.identity.common.store.MongoNamedStore;
import com.iaf.console.identity.iam.domain.Group;
import com.iaf.console.identity.iam.ports.outbound.GroupStore;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.Indexes;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Collection {@code groups}, with {@code idpGroups} beside the record so sign-in finds the groups a provider group joins. */
public final class MongoGroupStore implements GroupStore, MongoIndexed {

    private static final Keyed<Group> KEYS = new Keyed<>() {
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
    };

    private final MongoNamedStore<Group> store;

    public MongoGroupStore(Mongo mongo) {
        this.store = new MongoNamedStore<>(mongo, "groups", Group.class, KEYS, "group");
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return store.ensureIndexes(List.of(new IndexModel(Indexes.ascending("idpGroups"))));
    }

    @Override
    public Mono<Group> insert(Group group) {
        return store.insert(group, new Document("idpGroups", group.idpGroups()));
    }

    @Override
    public Mono<Group> update(Group group, long expectedVersion) {
        return store.update(group, expectedVersion, new Document("idpGroups", group.idpGroups()));
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
        return store.findWhere(Filters.in("idpGroups", idpGroups));
    }
}
