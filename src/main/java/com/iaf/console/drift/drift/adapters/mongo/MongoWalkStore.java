package com.iaf.console.drift.drift.adapters.mongo;

import org.bson.Document;

import com.iaf.console.drift.drift.domain.Walk;
import com.iaf.console.drift.drift.ports.outbound.WalkStorePort;
import com.iaf.console.kit.mongo.Mongo;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;

import reactor.core.publisher.Mono;

/**
 * The lists' walks in MongoDB, collection {@code drift_walks}: one document per list, keyed by the
 * walk's key, written only on the revision it was read at, so two replicas' turns never both hold a
 * walk. The key is the {@code _id}, the only thing looked up.
 */
public final class MongoWalkStore implements WalkStorePort {

    static final String COLLECTION = "drift_walks";

    private final Mongo mongo;

    public MongoWalkStore(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Walk> find(String key) {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.eq("_id", key)).first()).map(d -> mongo.read(d, Walk.class));
    }

    @Override
    public Mono<Walk> save(Walk walk) {
        Walk next = walk.withRev(walk.rev() + 1);
        Document doc = new Document("_id", walk.key()).append("rev", next.rev()).append(Mongo.DOC, mongo.doc(next));
        if (walk.rev() == 0) {
            return Mono.from(mongo.collection(COLLECTION).insertOne(doc))
                    .thenReturn(next)
                    .onErrorMap(MongoException.class, e -> Mongo.duplicateKey(e) ? new Conflict(walk.key()) : e);
        }
        return Mono.from(mongo.collection(COLLECTION).replaceOne(Filters.and(Filters.eq("_id", walk.key()), Filters.eq("rev", walk.rev())), doc))
                .flatMap(r -> r.getMatchedCount() == 1 ? Mono.just(next) : Mono.error(new Conflict(walk.key())));
    }
}
