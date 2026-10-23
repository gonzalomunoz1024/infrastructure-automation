package com.iaf.console.state.inventory.adapters.mongo;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.bson.Document;

import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.state.inventory.application.InventoryService;
import com.iaf.console.state.inventory.domain.ResourceEvent;
import com.iaf.console.state.inventory.ports.outbound.ResourceEventStorePort;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The log in MongoDB: collection {@code resource_events}, one document per event with the event
 * under {@code doc} and the queried fields beside it, {@code _id} {@code <wfrn>#<version>} so the
 * unique key is the optimistic lock, and a TTL on {@code expiresAt} that drops a tombstone's log
 * when its period lapses. Sharding, when it comes, hashes {@code wfrn}.
 */
public final class MongoResourceEventStore implements ResourceEventStorePort, MongoIndexed {

    static final String COLLECTION = "resource_events";

    private final Mongo mongo;

    public MongoResourceEventStore(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return mongo.indexes(COLLECTION, List.of(
                new IndexModel(Indexes.ascending("wfrn", "version"), new IndexOptions().unique(true)),
                new IndexModel(Indexes.ascending("accountId", "region", "atNs")),
                new IndexModel(Indexes.ascending("expiresAt"), new IndexOptions().expireAfter(0L, TimeUnit.SECONDS))));
    }

    @Override
    public Mono<ResourceEvent> append(ResourceEvent event) {
        Document d = new Document("_id", event.wfrn() + "#" + event.version())
                .append("wfrn", event.wfrn())
                .append("accountId", event.accountId())
                .append("region", event.region())
                .append("type", event.type().wire())
                .append("version", event.version())
                .append("key", event.key())
                .append("atNs", Mongo.nanos(event.at()))
                .append("at", Mongo.date(event.at()))
                .append(Mongo.DOC, mongo.doc(event));
        return Mono.from(mongo.collection(COLLECTION).insertOne(d))
                .thenReturn(event)
                .onErrorMap(MongoException.class, e -> Mongo.duplicateKey(e)
                        ? new ConflictException(InventoryService.VERSION_CONFLICT + ": version " + event.version() + " of " + event.wfrn()
                                + " is already written")
                        : e);
    }

    @Override
    public Flux<ResourceEvent> findEvents(AccountScope scope, String wfrn, long afterVersion, int limit) {
        if (wfrn == null || limit <= 0) {
            return Flux.empty();
        }
        return Flux.from(mongo.collection(COLLECTION)
                .find(Filters.and(Filters.eq("wfrn", wfrn), Filters.eq("accountId", scope.accountId()), Filters.eq("region", scope.region()),
                        Filters.gt("version", afterVersion)))
                .sort(Sorts.ascending("version"))
                .limit(limit))
                .map(d -> mongo.read(d, ResourceEvent.class));
    }

    @Override
    public Mono<Void> expireAt(String wfrn, Instant at) {
        return Mono.from(mongo.collection(COLLECTION).updateMany(Filters.eq("wfrn", wfrn), Updates.set("expiresAt", Mongo.date(at)))).then();
    }

    @Override
    public Mono<Long> countAll() {
        return Mono.from(mongo.collection(COLLECTION).estimatedDocumentCount());
    }
}
