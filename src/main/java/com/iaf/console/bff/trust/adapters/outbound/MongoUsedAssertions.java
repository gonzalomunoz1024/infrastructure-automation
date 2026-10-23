package com.iaf.console.bff.trust.adapters.outbound;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.console.bff.trust.ports.outbound.UsedAssertionsPort;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
import com.mongodb.MongoException;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;

import reactor.core.publisher.Mono;

/**
 * The redeemed assertions of every replica, in {@code service_token_assertions}: {@code _id} the
 * assertion's key (the client and its jti, or the hash of a cluster token), {@code expiresAt} with
 * a TTL index. The first insert of a key wins; a duplicate key is a replay.
 */
@Component
@Profile("mongo")
public class MongoUsedAssertions implements UsedAssertionsPort, MongoIndexed {

    static final String COLLECTION = "service_token_assertions";

    private final Mongo mongo;

    public MongoUsedAssertions(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return mongo.indexes(COLLECTION, List.of(new IndexModel(Indexes.ascending("expiresAt"), new IndexOptions().expireAfter(0L, TimeUnit.SECONDS))));
    }

    @Override
    public Mono<Boolean> firstUse(String key, Instant forgetAfter) {
        return Mono.from(mongo.collection(COLLECTION).insertOne(new Document("_id", key).append("expiresAt", Mongo.date(forgetAfter))))
                .thenReturn(true)
                .onErrorResume(MongoException.class, e -> Mongo.duplicateKey(e) ? Mono.just(false) : Mono.error(e));
    }
}
