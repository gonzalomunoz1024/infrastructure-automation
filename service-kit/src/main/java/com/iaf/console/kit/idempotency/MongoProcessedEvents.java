package com.iaf.console.kit.idempotency;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.bson.Document;

import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;

import reactor.core.publisher.Mono;

/**
 * The processed keys of the {@code mongo} profile: collection {@code processed_events}, {@code _id}
 * the key, and a TTL index on {@code expiresAt} that drops a key once its time is up.
 */
public final class MongoProcessedEvents implements ProcessedEventsPort, MongoIndexed {

    static final String COLLECTION = "processed_events";

    private final Mongo mongo;

    public MongoProcessedEvents(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return mongo.indexes(COLLECTION, List.of(new IndexModel(Indexes.ascending("expiresAt"), new IndexOptions().expireAfter(0L, TimeUnit.SECONDS))));
    }

    @Override
    public Mono<Boolean> markIfNew(String key, Instant now, Duration ttl) {
        Document d = new Document("_id", key).append("at", Mongo.date(now)).append("expiresAt", Mongo.date(now.plus(ttl)));
        return Mono.from(mongo.collection(COLLECTION).insertOne(d))
                .thenReturn(true)
                .onErrorResume(MongoException.class, e -> Mongo.duplicateKey(e) ? Mono.just(false) : Mono.error(e));
    }

    @Override
    public Mono<Void> forget(String key) {
        return Mono.from(mongo.collection(COLLECTION).deleteOne(Filters.eq("_id", key))).then();
    }
}
