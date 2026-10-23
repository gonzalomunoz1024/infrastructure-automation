package com.iaf.console.cost.ledger.adapters.mongo;

import java.time.Instant;
import java.util.List;

import org.bson.Document;

import com.iaf.console.cost.ledger.ports.outbound.CheckpointPort;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.ReplaceOptions;

import reactor.core.publisher.Mono;

/** The poller's watermarks in MongoDB: one document per source. */
public final class MongoCheckpoints implements CheckpointPort, MongoIndexed {

    static final String COLLECTION = "cost_checkpoints";

    private final Mongo mongo;

    public MongoCheckpoints(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return mongo.indexes(COLLECTION, List.<IndexModel>of());
    }

    @Override
    public Mono<Instant> find(String source) {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.eq("_id", source)).first()).map(d -> Instant.parse(d.getString("at")));
    }

    @Override
    public Mono<Void> save(String source, Instant at) {
        return Mono.from(mongo.collection(COLLECTION).replaceOne(Filters.eq("_id", source), new Document("_id", source).append("at", at.toString()),
                new ReplaceOptions().upsert(true))).then();
    }
}
