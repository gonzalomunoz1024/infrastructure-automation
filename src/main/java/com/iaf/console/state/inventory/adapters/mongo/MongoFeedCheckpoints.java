package com.iaf.console.state.inventory.adapters.mongo;

import java.time.Instant;

import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
import com.iaf.console.state.inventory.ports.outbound.FeedCheckpointPort;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;

import reactor.core.publisher.Mono;

/** Feed checkpoints in MongoDB: collection {@code feed_checkpoints}, {@code _id} the feed name, {@code seq} only ever grows. */
public final class MongoFeedCheckpoints implements FeedCheckpointPort, MongoIndexed {

    static final String COLLECTION = "feed_checkpoints";

    private final Mongo mongo;

    public MongoFeedCheckpoints(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return Mono.empty();
    }

    @Override
    public Mono<Long> find(String feed) {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.eq("_id", feed)).first()).map(d -> ((Number) d.get("seq")).longValue());
    }

    @Override
    public Mono<Void> save(String feed, long seq) {
        return Mono.from(mongo.collection(COLLECTION).updateOne(Filters.eq("_id", feed),
                Updates.combine(Updates.max("seq", seq), Updates.set("at", Mongo.date(Instant.now()))), new UpdateOptions().upsert(true))).then();
    }
}
