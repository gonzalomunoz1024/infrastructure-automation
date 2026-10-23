package com.iaf.console.kit.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.bson.Document;
import org.bson.conversions.Bson;

import com.iaf.console.kit.events.EventEnvelope;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
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
 * The outbox of the {@code mongo} profile: collection {@code outbox}, {@code _id} the sequence from
 * the {@code store_sequences} counter, a unique index on {@code key} so a duplicate append is a
 * no-op, and the relay lease in {@code outbox_state}. One document per event, the envelope under
 * {@code doc}, the fields the relay queries beside it.
 */
public final class MongoOutbox implements OutboxPort, MongoIndexed {

    static final String COLLECTION = "outbox";
    static final String STATE = "outbox_state";
    static final String SEQUENCE = "outbox";
    static final String LEASE = "relay-lease";

    private final Mongo mongo;

    public MongoOutbox(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return mongo.indexes(COLLECTION, List.of(
                new IndexModel(Indexes.ascending("key"), new IndexOptions().unique(true)),
                new IndexModel(Indexes.ascending("publishedAt", "_id")),
                new IndexModel(Indexes.ascending("createdAt"))));
    }

    @Override
    public Mono<OutboxEntry> append(String topic, String partitionKey, EventEnvelope event) {
        return mongo.nextSequence(SEQUENCE).flatMap(seq -> {
            Instant now = event.at() == null ? Instant.now() : event.at();
            OutboxEntry entry = new OutboxEntry(seq, event.key(), topic, partitionKey, event.withSeq(seq), now, null, 0);
            return Mono.from(mongo.collection(COLLECTION).insertOne(document(entry)))
                    .thenReturn(entry)
                    .onErrorResume(MongoException.class, e -> Mongo.duplicateKey(e) ? existing(event.key()) : Mono.error(e));
        });
    }

    private Mono<OutboxEntry> existing(String key) {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.eq("key", key)).first()).map(this::read);
    }

    @Override
    public Flux<OutboxEntry> pending(int limit) {
        return Flux.from(mongo.collection(COLLECTION).find(Filters.eq("publishedAt", null)).sort(Sorts.ascending("_id")).limit(Math.max(0, limit)))
                .map(this::read);
    }

    @Override
    public Mono<Void> markPublished(long seq, Instant at) {
        return Mono.from(mongo.collection(COLLECTION).updateOne(Filters.eq("_id", seq), Updates.set("publishedAt", Mongo.date(at)))).then();
    }

    @Override
    public Mono<Void> markFailed(long seq) {
        return Mono.from(mongo.collection(COLLECTION).updateOne(Filters.eq("_id", seq), Updates.inc("attempts", 1))).then();
    }

    @Override
    public Mono<Long> highWater() {
        return Mono.from(mongo.collection(COLLECTION).find().sort(Sorts.descending("_id")).limit(1).first())
                .map(d -> ((Number) d.get("_id")).longValue())
                .defaultIfEmpty(0L);
    }

    @Override
    public Mono<Boolean> acquireLease(String owner, Duration ttl, Instant now) {
        Bson free = Filters.and(Filters.eq("_id", LEASE), Filters.or(Filters.eq("owner", owner), Filters.lt("until", Mongo.date(now)),
                Filters.exists("owner", false)));
        Bson claim = Updates.combine(Updates.set("owner", owner), Updates.set("until", Mongo.date(now.plus(ttl))));
        return Mono.from(mongo.collection(STATE).updateOne(free, claim))
                .flatMap(r -> {
                    if (r.getModifiedCount() > 0 || r.getMatchedCount() > 0) {
                        return Mono.just(true);
                    }
                    // no lease document yet: the first relay creates it; a loser hits the duplicate key
                    return Mono.from(mongo.collection(STATE).insertOne(new Document("_id", LEASE).append("owner", owner)
                                    .append("until", Mongo.date(now.plus(ttl)))))
                            .thenReturn(true)
                            .onErrorResume(MongoException.class, e -> Mongo.duplicateKey(e) ? Mono.just(false) : Mono.error(e));
                });
    }

    @Override
    public Mono<Void> releaseLease(String owner) {
        return Mono.from(mongo.collection(STATE).updateOne(Filters.and(Filters.eq("_id", LEASE), Filters.eq("owner", owner)),
                Updates.combine(Updates.unset("owner"), Updates.set("until", Mongo.date(Instant.EPOCH))))).then();
    }

    @Override
    public Flux<OutboxEntry> after(long seq, int limit) {
        return Flux.from(mongo.collection(COLLECTION).find(Filters.gt("_id", seq)).sort(Sorts.ascending("_id")).limit(Math.max(0, limit)))
                .map(this::read);
    }

    private Document document(OutboxEntry e) {
        return new Document("_id", e.seq())
                .append("key", e.key())
                .append("topic", e.topic())
                .append("partitionKey", e.partitionKey())
                .append("type", e.event().type())
                .append("accountId", e.event().accountId())
                .append("createdAt", Mongo.date(e.createdAt()))
                .append("publishedAt", null)
                .append("attempts", 0)
                .append(Mongo.DOC, mongo.doc(e.event()));
    }

    private OutboxEntry read(Document d) {
        EventEnvelope event = mongo.read(d, EventEnvelope.class);
        return new OutboxEntry(((Number) d.get("_id")).longValue(), d.getString("key"), d.getString("topic"), d.getString("partitionKey"), event,
                Mongo.instant(d.getDate("createdAt")), Mongo.instant(d.getDate("publishedAt")), d.get("attempts") == null ? 0
                        : ((Number) d.get("attempts")).intValue());
    }
}
