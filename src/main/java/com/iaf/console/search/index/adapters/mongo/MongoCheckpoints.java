package com.iaf.console.search.index.adapters.mongo;

import java.util.List;

import org.bson.Document;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.ReplaceOptions;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
import com.iaf.console.search.index.domain.Checkpoint;
import com.iaf.console.search.index.ports.outbound.CheckpointPort;

import reactor.core.publisher.Mono;

/** The checkpoints in MongoDB: one document per source. */
public final class MongoCheckpoints implements CheckpointPort, MongoIndexed {

    static final String COLLECTION = "checkpoints";

    private final Mongo mongo;

    public MongoCheckpoints(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return mongo.indexes(COLLECTION, List.<IndexModel>of());
    }

    @Override
    public Mono<Checkpoint> save(Checkpoint checkpoint) {
        Document d = new Document("_id", checkpoint.source()).append(Mongo.DOC, mongo.doc(checkpoint));
        return Mono.from(mongo.collection(COLLECTION).replaceOne(Filters.eq("_id", checkpoint.source()), d, new ReplaceOptions().upsert(true)))
                .thenReturn(checkpoint);
    }

    @Override
    public Mono<Checkpoint> find(String source) {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.eq("_id", source)).first()).map(s -> mongo.read(s, Checkpoint.class));
    }
}
