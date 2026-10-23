package com.iaf.console.bff.clients.adapters.outbound;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.console.bff.clients.domain.ApiSession;
import com.iaf.console.bff.clients.ports.outbound.ApiSessionStore;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * API sessions shared by every instance, in the {@code api_sessions} collection: {@code _id} the
 * session id, the session under {@code doc}, and beside it what the queries read: the person's
 * {@code userId}, {@code createdAt} in epoch nanoseconds (the list's order), the current refresh
 * token's SHA-256 (unique) and the rotated ones' (for reuse detection), whether it was revoked,
 * {@code version}, and {@code endsAt}, its end absolute or idle, which a TTL index removes it at.
 */
@Component
@Profile("mongo")
public class MongoApiSessionStore implements ApiSessionStore, MongoIndexed {

    static final String COLLECTION = "api_sessions";

    private final Mongo mongo;

    public MongoApiSessionStore(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return mongo.indexes(COLLECTION, List.of(
                new IndexModel(Indexes.ascending("refreshHash"), new IndexOptions().unique(true)),
                new IndexModel(Indexes.ascending("rotatedHashes")),
                new IndexModel(Indexes.compoundIndex(Indexes.ascending("userId"), Indexes.descending("createdAtNs", "_id"))),
                new IndexModel(Indexes.ascending("endsAt"), new IndexOptions().expireAfter(0L, TimeUnit.SECONDS))));
    }

    @Override
    public Mono<Void> create(ApiSession session) {
        return Mono.defer(() -> Mono.from(mongo.collection(COLLECTION).insertOne(document(session)))).then();
    }

    @Override
    public Mono<ApiSession> find(String id) {
        return first(Filters.eq("_id", id));
    }

    @Override
    public Mono<ApiSession> findByRefreshToken(String refreshHash) {
        return first(Filters.eq("refreshHash", refreshHash));
    }

    @Override
    public Mono<ApiSession> findByRotatedToken(String refreshHash) {
        return first(Filters.eq("rotatedHashes", refreshHash));
    }

    @Override
    public Mono<List<ApiSession>> listOpen(String userId, Instant now, int limit, Instant afterCreatedAt, String afterId) {
        Bson open = Filters.and(Filters.eq("userId", userId), Filters.eq("revoked", false), Filters.gt("endsAt", Mongo.date(now)));
        Bson filter = open;
        if (afterId != null) {
            Long after = Mongo.nanos(afterCreatedAt);
            filter = Filters.and(open, Filters.or(Filters.lt("createdAtNs", after), Filters.and(Filters.eq("createdAtNs", after), Filters.lt("_id", afterId))));
        }
        return Flux.from(mongo.collection(COLLECTION).find(filter).sort(Sorts.descending("createdAtNs", "_id")).limit(limit))
                .map(this::read)
                .collectList();
    }

    @Override
    public Mono<ApiSession> update(ApiSession session, long expectedVersion) {
        Bson current = Filters.and(Filters.eq("_id", session.id()), Filters.eq("version", expectedVersion));
        Bson next = Updates.combine(
                Updates.set("userId", session.user().userId()),
                Updates.set("refreshHash", session.refreshHash()),
                Updates.set("rotatedHashes", session.rotatedHashes()),
                Updates.set("revoked", session.revokedAt() != null),
                Updates.set("endsAt", Mongo.date(session.purgeAt())),
                Updates.set("version", session.version()),
                Updates.set(Mongo.DOC, mongo.doc(session)));
        return Mono.from(mongo.collection(COLLECTION).updateOne(current, next)).flatMap(r -> r.getMatchedCount() > 0 ? Mono.just(session) : Mono.empty());
    }

    /** A session as it is stored. */
    Document document(ApiSession session) {
        return new Document("_id", session.id())
                .append("userId", session.user().userId())
                .append("createdAtNs", Mongo.nanos(session.createdAt()))
                .append("refreshHash", session.refreshHash())
                .append("rotatedHashes", session.rotatedHashes())
                .append("revoked", session.revokedAt() != null)
                .append("endsAt", Mongo.date(session.purgeAt()))
                .append("version", session.version())
                .append(Mongo.DOC, mongo.doc(session));
    }

    ApiSession read(Document d) {
        return mongo.read(d, ApiSession.class);
    }

    private Mono<ApiSession> first(Bson filter) {
        return Mono.from(mongo.collection(COLLECTION).find(filter).first()).map(this::read);
    }
}
