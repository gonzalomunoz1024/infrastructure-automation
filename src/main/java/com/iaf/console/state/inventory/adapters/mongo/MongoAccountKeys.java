package com.iaf.console.state.inventory.adapters.mongo;

import org.bson.Document;
import org.bson.types.Binary;

import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
import com.iaf.console.state.inventory.ports.outbound.AccountKeysPort;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The wrapped data keys in MongoDB: collection {@code account_keys}, {@code _id} the account. Destroying one is the crypto-shred. */
public final class MongoAccountKeys implements AccountKeysPort, MongoIndexed {

    static final String COLLECTION = "account_keys";

    private final Mongo mongo;

    public MongoAccountKeys(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return Mono.empty();
    }

    @Override
    public Mono<WrappedKey> find(String accountId) {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.and(Filters.eq("_id", accountId), Filters.ne("shredded", true))).first())
                .map(MongoAccountKeys::read);
    }

    @Override
    public Mono<WrappedKey> save(WrappedKey key) {
        Document d = new Document("_id", key.accountId())
                .append("version", key.version())
                .append("wrapped", new Binary(key.wrapped()))
                .append("nonce", new Binary(key.nonce()))
                .append("createdAt", Mongo.date(key.createdAt()));
        return Mono.from(mongo.collection(COLLECTION).insertOne(d))
                .thenReturn(key)
                .onErrorResume(MongoException.class, e -> Mongo.duplicateKey(e) ? find(key.accountId()) : Mono.error(e));
    }

    @Override
    public Mono<Boolean> destroy(String accountId) {
        // the key goes, a marker stays: a shredded account never gets a new key
        Document marker = new Document("_id", accountId).append("shredded", true).append("shreddedAt", new java.util.Date());
        return Mono.from(mongo.collection(COLLECTION).findOneAndReplace(Filters.eq("_id", accountId), marker,
                        new com.mongodb.client.model.FindOneAndReplaceOptions().upsert(true)))
                .map(before -> !before.getBoolean("shredded", false))
                .defaultIfEmpty(false);
    }

    @Override
    public Flux<String> listAccounts() {
        return Flux.from(mongo.collection(COLLECTION).find(Filters.ne("shredded", true)).projection(Projections.include("_id"))).map(d -> d.getString("_id"));
    }

    private static WrappedKey read(Document d) {
        return new WrappedKey(d.getString("_id"), d.getInteger("version", 1), d.get("wrapped", Binary.class).getData(),
                d.get("nonce", Binary.class).getData(), Mongo.instant(d.getDate("createdAt")));
    }
}
