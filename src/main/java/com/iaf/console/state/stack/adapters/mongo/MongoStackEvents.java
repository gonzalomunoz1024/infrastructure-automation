package com.iaf.console.state.stack.adapters.mongo;

import java.util.List;

import org.bson.Document;

import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.state.stack.domain.StackEvent;
import com.iaf.console.state.stack.ports.outbound.StackEventStorePort;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoBulkWriteException;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.InsertManyOptions;
import com.mongodb.client.model.Sorts;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Stacks' logs in MongoDB, collection {@code stack_events}: {@code _id} the event's key, one event per stack version. */
public final class MongoStackEvents implements StackEventStorePort, MongoIndexed {

    public static final String COLLECTION = "stack_events";

    private final Mongo mongo;

    public MongoStackEvents(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return mongo.indexes(COLLECTION, List.of(new IndexModel(Indexes.ascending("wfrn", "version"), new IndexOptions().unique(true))));
    }

    @Override
    public Mono<Void> append(List<StackEvent> events) {
        if (events.isEmpty()) {
            return Mono.empty();
        }
        List<Document> docs = events.stream().map(e -> new Document("_id", e.key())
                .append("wfrn", e.wfrn())
                .append("accountId", e.accountId())
                .append("region", e.region())
                .append("version", e.version())
                .append(Mongo.DOC, mongo.doc(e))).toList();
        return Mono.from(mongo.collection(COLLECTION).insertMany(docs, new InsertManyOptions().ordered(false))).then()
                .onErrorResume(MongoException.class, e -> duplicates(e) ? Mono.empty() : Mono.error(e));
    }

    /** Every write that failed met an event already logged: a replay of the same decision. */
    private static boolean duplicates(MongoException e) {
        if (e instanceof MongoBulkWriteException bulk) {
            return bulk.getWriteErrors().stream().allMatch(w -> ErrorCategory.fromErrorCode(w.getCode()) == ErrorCategory.DUPLICATE_KEY);
        }
        return Mongo.duplicateKey(e);
    }

    @Override
    public Flux<StackEvent> list(AccountScope scope, String wfrn, long afterVersion, int limit) {
        return Flux.from(mongo.collection(COLLECTION)
                .find(Filters.and(Filters.eq("wfrn", wfrn), Filters.eq("accountId", scope.accountId()), Filters.eq("region", scope.region()),
                        Filters.gt("version", afterVersion)))
                .sort(Sorts.ascending("version")).limit(limit))
                .map(d -> mongo.read(d, StackEvent.class));
    }
}
