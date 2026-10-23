package com.iaf.console.cost.ledger.adapters.mongo;

import java.time.LocalDate;
import java.util.List;

import org.bson.Document;

import com.iaf.console.cost.ledger.domain.RateDelta;
import com.iaf.console.cost.ledger.ports.outbound.DeltaStorePort;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
import com.iaf.console.kit.scope.AccountScope;
import com.mongodb.MongoBulkWriteException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.InsertManyOptions;
import com.mongodb.client.model.Sorts;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The deltas in MongoDB, keyed by their deterministic id; a delta already stored is a duplicate key and kept. */
public final class MongoDeltas implements DeltaStorePort, MongoIndexed {

    static final String COLLECTION = "cost_deltas";

    private final Mongo mongo;

    public MongoDeltas(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return mongo.indexes(COLLECTION, List.of(new IndexModel(Indexes.ascending("accountId", "day", "at"), new IndexOptions().name("account_day"))));
    }

    @Override
    public Mono<Void> insertAll(List<RateDelta> deltas) {
        if (deltas.isEmpty()) {
            return Mono.empty();
        }
        List<Document> docs = deltas.stream().map(d -> new Document("_id", d.id()).append("accountId", d.accountId()).append("day", d.day().toString())
                .append("at", Mongo.nanos(d.at())).append(Mongo.DOC, mongo.doc(d))).toList();
        return Mono.from(mongo.collection(COLLECTION).insertMany(docs, new InsertManyOptions().ordered(false))).then()
                .onErrorResume(MongoBulkWriteException.class, e -> e.getWriteErrors().stream().allMatch(w -> w.getCode() == 11000)
                        ? Mono.empty() : Mono.error(e));
    }

    @Override
    public Flux<RateDelta> listDays(AccountScope scope, LocalDate from, LocalDate to) {
        return Flux.from(mongo.collection(COLLECTION)
                .find(Filters.and(Filters.eq("accountId", scope.accountId()), Filters.gte("day", from.toString()), Filters.lt("day", to.toString())))
                .sort(Sorts.ascending("at", "_id"))).map(d -> mongo.read(d, RateDelta.class));
    }
}
