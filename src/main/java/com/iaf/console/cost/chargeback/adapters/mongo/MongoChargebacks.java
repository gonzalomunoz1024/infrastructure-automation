package com.iaf.console.cost.chargeback.adapters.mongo;

import java.time.Instant;
import java.util.List;

import org.bson.Document;

import com.iaf.console.cost.chargeback.domain.ChargebackMonth;
import com.iaf.console.cost.chargeback.ports.outbound.ChargebackStorePort;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
import com.iaf.console.kit.scope.AccountScope;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Updates;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The published months in MongoDB: one document per account and month, inserted once and never replaced. */
public final class MongoChargebacks implements ChargebackStorePort, MongoIndexed {

    static final String COLLECTION = "chargeback_months";

    private final Mongo mongo;

    public MongoChargebacks(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return mongo.indexes(COLLECTION, List.of(new IndexModel(Indexes.ascending("undelivered"),
                new IndexOptions().name("undelivered").partialFilterExpression(Filters.eq("undelivered", true)))));
    }

    @Override
    public Mono<ChargebackMonth> find(AccountScope scope, String month) {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.eq("_id", scope.accountId() + ":" + month)).first())
                .map(d -> mongo.read(d, ChargebackMonth.class));
    }

    @Override
    public Mono<ChargebackMonth> insertIfAbsent(ChargebackMonth month) {
        Document d = new Document("_id", month.id()).append("accountId", month.accountId()).append("month", month.month()).append("undelivered", true)
                .append(Mongo.DOC, mongo.doc(month));
        return Mono.from(mongo.collection(COLLECTION).insertOne(d)).thenReturn(month)
                .onErrorResume(MongoException.class, e -> Mongo.duplicateKey(e)
                        ? Mono.from(mongo.collection(COLLECTION).find(Filters.eq("_id", month.id())).first()).map(s -> mongo.read(s, ChargebackMonth.class))
                        : Mono.error(e));
    }

    @Override
    public Flux<ChargebackMonth> listUndelivered(int limit) {
        return Flux.from(mongo.collection(COLLECTION).find(Filters.eq("undelivered", true)).limit(limit)).map(d -> mongo.read(d, ChargebackMonth.class));
    }

    @Override
    public Mono<Void> markDelivered(String accountId, String month, Instant at) {
        return Mono.from(mongo.collection(COLLECTION).updateOne(Filters.and(Filters.eq("_id", accountId + ":" + month), Filters.eq("undelivered", true)),
                Updates.combine(Updates.set(Mongo.DOC + ".deliveredAt", at.toString()), Updates.unset("undelivered")))).then();
    }
}
