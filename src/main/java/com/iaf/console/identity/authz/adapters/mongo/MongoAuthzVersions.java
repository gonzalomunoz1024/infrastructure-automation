package com.iaf.console.identity.authz.adapters.mongo;

import java.util.List;

import org.bson.Document;

import com.iaf.console.identity.authz.ports.outbound.AuthzVersionPort;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;

import reactor.core.publisher.Mono;

/** Collection {@code authz_versions}: one counter per account and the {@code global} revision, grown with {@code $inc}. */
public final class MongoAuthzVersions implements AuthzVersionPort, MongoIndexed {

    static final String COLLECTION = "authz_versions";
    static final String GLOBAL = "global";

    private final Mongo mongo;

    public MongoAuthzVersions(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return mongo.indexes(COLLECTION, List.of());
    }

    @Override
    public Mono<Long> bump(String accountId) {
        return increment(GLOBAL).then(increment(accountId));
    }

    private Mono<Long> increment(String id) {
        return Mono.from(mongo.collection(COLLECTION).findOneAndUpdate(Filters.eq("_id", id), Updates.inc("value", 1L),
                new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER))).map(d -> ((Number) d.get("value")).longValue());
    }

    @Override
    public Mono<Long> getRevision() {
        return read(GLOBAL);
    }

    @Override
    public Mono<Long> getVersion(String accountId) {
        return read(accountId);
    }

    private Mono<Long> read(String id) {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.eq("_id", id)).first()).map(Document.class::cast)
                .map(d -> ((Number) d.get("value")).longValue()).defaultIfEmpty(0L);
    }
}
