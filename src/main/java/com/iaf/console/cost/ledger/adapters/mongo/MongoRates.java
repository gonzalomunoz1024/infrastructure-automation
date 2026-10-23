package com.iaf.console.cost.ledger.adapters.mongo;

import java.util.List;

import org.bson.Document;

import com.iaf.console.cost.ledger.domain.RateRecord;
import com.iaf.console.cost.ledger.ports.outbound.RateStorePort;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
import com.iaf.console.kit.scope.AccountScope;
import com.mongodb.MongoException;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Updates;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The rate records in MongoDB: one document per wfrn, {@code rev} beside the record for the
 * compare-and-set, {@code pending} true while its deltas are not known to be stored (a sparse
 * index finds the few a crash left), {@code charged} and {@code unpriced} for the counts.
 */
public final class MongoRates implements RateStorePort, MongoIndexed {

    static final String COLLECTION = "cost_rates";

    private final Mongo mongo;

    public MongoRates(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return mongo.indexes(COLLECTION, List.of(
                new IndexModel(Indexes.ascending("pending"), new IndexOptions().name("pending").partialFilterExpression(Filters.eq("pending", true))),
                new IndexModel(Indexes.ascending("accountId", "type"), new IndexOptions().name("account_type"))));
    }

    @Override
    public Mono<RateRecord> find(AccountScope scope, String wfrn) {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.and(Filters.eq("_id", wfrn), Filters.eq("accountId", scope.accountId()))).first())
                .map(d -> mongo.read(d, RateRecord.class));
    }

    @Override
    public Mono<Boolean> save(RateRecord record, long expectedRev) {
        Document d = document(record);
        if (expectedRev == 0) {
            return Mono.from(mongo.collection(COLLECTION).insertOne(d)).thenReturn(true)
                    .onErrorResume(MongoException.class, e -> Mongo.duplicateKey(e) ? Mono.just(false) : Mono.error(e));
        }
        return Mono.from(mongo.collection(COLLECTION).replaceOne(Filters.and(Filters.eq("_id", record.wfrn()), Filters.eq("rev", expectedRev)), d))
                .map(r -> r.getMatchedCount() == 1);
    }

    @Override
    public Flux<RateRecord> listPending(int limit) {
        return Flux.from(mongo.collection(COLLECTION).find(Filters.eq("pending", true)).limit(limit)).map(d -> mongo.read(d, RateRecord.class));
    }

    @Override
    public Mono<Void> markFlushed(String wfrn, long rev) {
        return Mono.from(mongo.collection(COLLECTION).updateOne(Filters.and(Filters.eq("_id", wfrn), Filters.eq("rev", rev)),
                Updates.combine(Updates.set(Mongo.DOC + ".pending", List.of()), Updates.unset("pending")))).then();
    }

    @Override
    public Mono<long[]> countAll() {
        return Mono.from(mongo.collection(COLLECTION).aggregate(List.of(
                Aggregates.match(Filters.eq("charged", true)),
                Aggregates.group(null, Accumulators.sum("charged", 1), Accumulators.sum("unpriced", new Document("$cond", List.of("$unpriced", 1, 0)))))))
                .map(d -> new long[] {((Number) d.get("charged")).longValue(), ((Number) d.get("unpriced")).longValue()})
                .defaultIfEmpty(new long[] {0, 0});
    }

    private Document document(RateRecord r) {
        Document d = new Document("_id", r.wfrn()).append("accountId", r.accountId()).append("type", r.type()).append("rev", r.rev())
                .append("charged", r.charged()).append("unpriced", r.charged() && r.contribution().unpriced() > 0).append(Mongo.DOC, mongo.doc(r));
        if (!r.pending().isEmpty()) {
            d.append("pending", true);
        }
        return d;
    }
}
