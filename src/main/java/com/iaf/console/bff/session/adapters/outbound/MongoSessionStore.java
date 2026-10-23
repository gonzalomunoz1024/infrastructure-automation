package com.iaf.console.bff.session.adapters.outbound;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.bff.session.domain.SessionKeys;
import com.iaf.console.bff.session.ports.outbound.SessionStore;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;

import reactor.core.publisher.Mono;

/**
 * Sessions shared by every instance, in the {@code sessions} collection: {@code _id} the SHA-256
 * of the session id (the id itself is never stored, not even in the record), the record under
 * {@code doc}, {@code userId} for an operator's lookup and a TTL index on {@code expiresAt} that
 * drops a session when its time is up. A session is inserted once, at sign-in; every later write
 * replaces it only while it is there, so a logout is never undone by a request in flight.
 */
@Component
@Profile("mongo")
public class MongoSessionStore implements SessionStore, MongoIndexed {

    static final String COLLECTION = "sessions";

    private final Mongo mongo;

    public MongoSessionStore(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return mongo.indexes(COLLECTION, List.of(
                new IndexModel(Indexes.ascending("expiresAt"), new IndexOptions().expireAfter(0L, TimeUnit.SECONDS)),
                new IndexModel(Indexes.ascending("userId"))));
    }

    @Override
    public Mono<Session> find(String id) {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.eq("_id", SessionKeys.of(id))).first())
                .map(d -> mongo.read(d, Session.class).withId(id));
    }

    @Override
    public Mono<Void> create(Session session) {
        return Mono.from(mongo.collection(COLLECTION).insertOne(document(session))).then();
    }

    @Override
    public Mono<Boolean> update(Session session) {
        Document d = document(session);
        return Mono.from(mongo.collection(COLLECTION).replaceOne(Filters.eq("_id", d.get("_id")), d)).map(r -> r.getMatchedCount() > 0);
    }

    @Override
    public Mono<Void> delete(String id) {
        return Mono.from(mongo.collection(COLLECTION).deleteOne(Filters.eq("_id", SessionKeys.of(id)))).then();
    }

    /** The stored document: keyed on the id's hash, with the hash in place of the id in the record. */
    Document document(Session session) {
        String key = SessionKeys.of(session.id());
        return new Document("_id", key)
                .append("userId", session.user().userId())
                .append("accountId", session.accountId())
                .append("expiresAt", Mongo.date(session.expiresAt()))
                .append(Mongo.DOC, mongo.doc(session.withId(key)));
    }
}
