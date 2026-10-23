package com.iaf.console.cost.budgets.adapters.mongo;

import java.util.List;

import org.bson.Document;
import org.bson.conversions.Bson;

import com.iaf.console.cost.budgets.domain.BudgetPeriod;
import com.iaf.console.cost.budgets.ports.outbound.BudgetPeriodStorePort;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
import com.iaf.console.kit.scope.AccountScope;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The budgets' months in MongoDB: one document per budget and month. */
public final class MongoBudgetPeriods implements BudgetPeriodStorePort, MongoIndexed {

    static final String COLLECTION = "budget_periods";

    private final Mongo mongo;

    public MongoBudgetPeriods(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return mongo.indexes(COLLECTION, List.of(new IndexModel(Indexes.ascending("budgetId", "period"), new IndexOptions().name("budget_period"))));
    }

    @Override
    public Mono<BudgetPeriod> find(AccountScope scope, String budgetId, String period) {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.and(Filters.eq("_id", budgetId + ":" + period),
                Filters.eq("accountId", scope.accountId()))).first()).map(d -> mongo.read(d, BudgetPeriod.class));
    }

    @Override
    public Flux<BudgetPeriod> list(AccountScope scope, String budgetId, String beforePeriod, int limit) {
        Bson filter = Filters.and(Filters.eq("budgetId", budgetId), Filters.eq("accountId", scope.accountId()));
        if (beforePeriod != null) {
            filter = Filters.and(filter, Filters.lt("period", beforePeriod));
        }
        return Flux.from(mongo.collection(COLLECTION).find(filter).sort(Sorts.descending("period")).limit(limit + 1))
                .map(d -> mongo.read(d, BudgetPeriod.class));
    }

    @Override
    public Mono<Void> save(BudgetPeriod p) {
        Document d = new Document("_id", p.id()).append("budgetId", p.budgetId()).append("accountId", p.accountId()).append("period", p.period())
                .append(Mongo.DOC, mongo.doc(p));
        return Mono.from(mongo.collection(COLLECTION).replaceOne(Filters.eq("_id", p.id()), d, new ReplaceOptions().upsert(true))).then();
    }

    @Override
    public Mono<Void> deleteAll(String accountId, String budgetId) {
        return Mono.from(mongo.collection(COLLECTION).deleteMany(Filters.and(Filters.eq("budgetId", budgetId), Filters.eq("accountId", accountId)))).then();
    }
}
