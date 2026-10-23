package com.iaf.console.bff.session.adapters.outbound;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.bff.session.ports.outbound.SessionStore;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;

import reactor.core.publisher.Mono;

/**
 * Sessions shared by every instance, in the {@code sessions} collection: {@code _id} the session
 * id, the record under {@code doc}, {@code userId} for an operator's lookup and a TTL index on
 * {@code expiresAt} that drops a session when its time is up.
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
        return Mono.from(mongo.collection(COLLECTION).find(Filters.eq("_id", id)).first()).map(d -> mongo.read(d, Session.class));
    }

    @Override
    public Mono<Void> save(Session session) {
        Document d = new Document("_id", session.id())
                .append("userId", session.user().userId())
                .append("accountId", session.accountId())
                .append("expiresAt", Mongo.date(session.expiresAt()))
                .append(Mongo.DOC, mongo.doc(session));
        return Mono.from(mongo.collection(COLLECTION).replaceOne(Filters.eq("_id", session.id()), d, new ReplaceOptions().upsert(true))).then();
    }

    @Override
    public Mono<Void> delete(String id) {
        return Mono.from(mongo.collection(COLLECTION).deleteOne(Filters.eq("_id", id))).then();
    }
}
