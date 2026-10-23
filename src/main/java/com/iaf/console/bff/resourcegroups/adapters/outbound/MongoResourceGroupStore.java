package com.iaf.console.bff.resourcegroups.adapters.outbound;

import java.util.List;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.console.bff.resourcegroups.domain.CreateKey;
import com.iaf.console.bff.resourcegroups.domain.CreatedGroup;
import com.iaf.console.bff.resourcegroups.domain.GroupNameTakenException;
import com.iaf.console.bff.resourcegroups.domain.ResourceGroup;
import com.iaf.console.bff.resourcegroups.ports.outbound.ResourceGroupStore;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
import com.iaf.console.kit.scope.AccountScope;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Updates;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Resource groups shared by every instance, in the {@code resource_groups} collection: {@code _id}
 * the group id, the group under {@code doc}, and beside it what the queries read: {@code accountId},
 * {@code region}, {@code name}, {@code version} and the create's Idempotency-Key ({@code createKey}).
 * A unique index on account, region and name refuses a second group of a name on insert.
 */
@Component
@Profile("mongo")
public class MongoResourceGroupStore implements ResourceGroupStore, MongoIndexed {

    static final String COLLECTION = "resource_groups";
    static final String CREATE_KEY = "createKey";

    private final Mongo mongo;

    public MongoResourceGroupStore(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return mongo.indexes(COLLECTION, List.of(new IndexModel(Indexes.ascending("accountId", "region", "name"), new IndexOptions().unique(true))));
    }

    @Override
    public Mono<List<ResourceGroup>> list(AccountScope scope) {
        return Flux.from(mongo.collection(COLLECTION).find(inScope(scope))).map(this::read).collectList();
    }

    @Override
    public Mono<ResourceGroup> get(AccountScope scope, String groupId) {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.and(inScope(scope), Filters.eq("_id", groupId))).first()).map(this::read);
    }

    @Override
    public Mono<CreatedGroup> findCreated(AccountScope scope, String idempotencyKey) {
        Bson filter = Filters.and(inScope(scope), Filters.eq(CREATE_KEY + ".principal", scope.principal()), Filters.eq(CREATE_KEY + ".key", idempotencyKey));
        return Flux.from(mongo.collection(COLLECTION).find(filter))
                .map(d -> new CreatedGroup(read(d), mongo.as(d.get(CREATE_KEY, Document.class), CreateKey.class)))
                .reduce((a, b) -> a.key().at().isAfter(b.key().at()) ? a : b);
    }

    @Override
    public Mono<ResourceGroup> create(AccountScope scope, ResourceGroup group, CreateKey key) {
        return Mono.defer(() -> Mono.from(mongo.collection(COLLECTION).insertOne(document(scope, group, key))))
                .thenReturn(group)
                .onErrorResume(MongoException.class, e -> Mongo.duplicateKey(e) ? Mono.error(new GroupNameTakenException(group.name())) : Mono.error(e));
    }

    @Override
    public Mono<ResourceGroup> update(AccountScope scope, ResourceGroup group, long expectedVersion) {
        Bson current = Filters.and(inScope(scope), Filters.eq("_id", group.groupId()), Filters.eq("version", expectedVersion));
        Bson next = Updates.combine(Updates.set("version", group.version()), Updates.set(Mongo.DOC, mongo.doc(group)));
        return Mono.from(mongo.collection(COLLECTION).updateOne(current, next)).flatMap(r -> r.getMatchedCount() > 0 ? Mono.just(group) : Mono.empty());
    }

    @Override
    public Mono<Boolean> delete(AccountScope scope, String groupId) {
        return Mono.from(mongo.collection(COLLECTION).deleteOne(Filters.and(inScope(scope), Filters.eq("_id", groupId))))
                .map(r -> r.getDeletedCount() > 0);
    }

    /** A group as it is stored, in the scope's account and region. */
    Document document(AccountScope scope, ResourceGroup group, CreateKey key) {
        if (!group.in(scope.accountId(), scope.region())) {
            throw new IllegalStateException("the group " + group.groupId() + " is not of the scope's account and region");
        }
        Document d = new Document("_id", group.groupId())
                .append("accountId", scope.accountId())
                .append("region", scope.region())
                .append("name", group.name())
                .append("version", group.version());
        if (key != null) {
            d.append(CREATE_KEY, mongo.doc(key));
        }
        return d.append(Mongo.DOC, mongo.doc(group));
    }

    ResourceGroup read(Document d) {
        return mongo.read(d, ResourceGroup.class);
    }

    private static Bson inScope(AccountScope scope) {
        return Filters.and(Filters.eq("accountId", scope.accountId()), Filters.eq("region", scope.region()));
    }
}
