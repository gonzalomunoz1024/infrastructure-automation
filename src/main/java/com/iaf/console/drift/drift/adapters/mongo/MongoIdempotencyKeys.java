package com.iaf.console.drift.drift.adapters.mongo;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.bson.Document;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.drift.drift.ports.outbound.IdempotencyKeyPort;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;

import reactor.core.publisher.Mono;

/** Idempotency keys in MongoDB: collection {@code idempotency_keys}, a TTL on {@code expiresAt}; the first answer under a key wins. */
public final class MongoIdempotencyKeys implements IdempotencyKeyPort, MongoIndexed {

    static final String COLLECTION = "idempotency_keys";

    private final Mongo mongo;

    public MongoIdempotencyKeys(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return mongo.indexes(COLLECTION, List.of(new IndexModel(Indexes.ascending("expiresAt"), new IndexOptions().expireAfter(0L, TimeUnit.SECONDS))));
    }

    @Override
    public Mono<StoredResponse> find(String id) {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.eq("_id", id)).first()).map(this::read);
    }

    @Override
    public Mono<StoredResponse> save(StoredResponse response, Duration ttl) {
        Document d = new Document("_id", response.id())
                .append("bodyHash", response.bodyHash())
                .append("status", response.status())
                .append("body", mongo.doc(response.body()))
                .append("at", Mongo.date(response.at()))
                .append("expiresAt", Mongo.date(response.at().plus(ttl)));
        return Mono.from(mongo.collection(COLLECTION).insertOne(d))
                .thenReturn(response)
                .onErrorResume(MongoException.class, e -> Mongo.duplicateKey(e) ? find(response.id()) : Mono.error(e));
    }

    private StoredResponse read(Document d) {
        Document body = d.get("body", Document.class);
        return new StoredResponse(d.getString("_id"), d.getString("bodyHash"), d.getInteger("status", 200),
                body == null ? null : mongo.as(body, ObjectNode.class), Mongo.instant(d.getDate("at")));
    }
}
