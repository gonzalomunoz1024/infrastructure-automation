package com.iaf.console.identity.common.idempotency;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.bson.types.Binary;

import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Updates;

import reactor.core.publisher.Mono;

/**
 * The {@code mongo} profile's store: collection {@code idempotency}, {@code _id} the scope, a TTL
 * index on {@code expiresAt}; the reservation is an insert that loses on a duplicate key.
 */
public final class MongoIdempotency implements IdempotencyPort, MongoIndexed {

    static final String COLLECTION = "idempotency";

    private final Mongo mongo;

    public MongoIdempotency(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return mongo.indexes(COLLECTION, List.of(new IndexModel(Indexes.ascending("expiresAt"), new IndexOptions().expireAfter(0L, TimeUnit.SECONDS))));
    }

    @Override
    public Mono<Reservation> reserve(String scope, String bodyHash, Instant now, Duration ttl) {
        Document fresh = new Document("_id", scope).append("bodyHash", bodyHash).append("createdAt", Mongo.date(now))
                .append("expiresAt", Mongo.date(now.plus(ttl)));
        return Mono.from(mongo.collection(COLLECTION).deleteOne(Filters.and(Filters.eq("_id", scope), Filters.lt("expiresAt", Mongo.date(now)))))
                .then(Mono.from(mongo.collection(COLLECTION).insertOne(fresh)))
                .map(r -> new Reservation(Outcome.RESERVED, read(fresh)))
                .onErrorResume(MongoException.class, e -> Mongo.duplicateKey(e) ? existing(scope, bodyHash) : Mono.error(e));
    }

    private Mono<Reservation> existing(String scope, String bodyHash) {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.eq("_id", scope)).first())
                .map(this::read)
                .map(r -> {
                    if (!r.bodyHash().equals(bodyHash)) {
                        return new Reservation(Outcome.MISMATCH, r);
                    }
                    return new Reservation(r.completed() ? Outcome.REPLAY : Outcome.IN_FLIGHT, r);
                })
                .switchIfEmpty(Mono.error(new IllegalStateException("the idempotency record vanished")));
    }

    @Override
    public Mono<Void> complete(String scope, int status, String contentType, byte[] body) {
        return Mono.from(mongo.collection(COLLECTION).updateOne(Filters.eq("_id", scope),
                Updates.combine(Updates.set("status", status), Updates.set("contentType", contentType), Updates.set("body", new Binary(body))))).then();
    }

    @Override
    public Mono<Void> release(String scope) {
        return Mono.from(mongo.collection(COLLECTION).deleteOne(Filters.eq("_id", scope))).then();
    }

    private IdempotencyRecord read(Document d) {
        Object stored = d.get("body");
        byte[] body = stored instanceof Binary b ? b.getData() : null;
        return new IdempotencyRecord(d.getString("_id"), d.getString("bodyHash"), d.getInteger("status"), d.getString("contentType"), body,
                Mongo.instant(d.getDate("createdAt")), Mongo.instant(d.getDate("expiresAt")));
    }
}
