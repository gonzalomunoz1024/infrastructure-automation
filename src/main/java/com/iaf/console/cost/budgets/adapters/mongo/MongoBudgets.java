package com.iaf.console.cost.budgets.adapters.mongo;

import java.util.List;
import java.util.Locale;

import org.bson.Document;
import org.bson.conversions.Bson;

import com.iaf.console.cost.budgets.domain.Budget;
import com.iaf.console.cost.budgets.ports.outbound.BudgetStorePort;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
import com.iaf.console.kit.scope.AccountScope;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The budgets in MongoDB: one document per budget with the account, the lower-cased name (the
 * list's order), the version (the compare-and-set) and the idempotency key (unique per account
 * where present) beside the record.
 */
public final class MongoBudgets implements BudgetStorePort, MongoIndexed {

    static final String COLLECTION = "budgets";

    private final Mongo mongo;

    public MongoBudgets(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return mongo.indexes(COLLECTION, List.of(
                new IndexModel(Indexes.ascending("accountId", "sortName", "_id"), new IndexOptions().name("account_name")),
                new IndexModel(Indexes.ascending("accountId", "idempotencyKey"), new IndexOptions().name("account_idempotency").unique(true)
                        .partialFilterExpression(Filters.exists("idempotencyKey")))));
    }

    @Override
    public Mono<Budget> find(AccountScope scope, String budgetId) {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.and(Filters.eq("_id", budgetId), Filters.eq("accountId", scope.accountId()))).first())
                .map(d -> mongo.read(d, Budget.class));
    }

    @Override
    public Mono<Budget> findByIdempotencyKey(AccountScope scope, String key) {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.and(Filters.eq("accountId", scope.accountId()), Filters.eq("idempotencyKey", key)))
                .first()).map(d -> mongo.read(d, Budget.class));
    }

    @Override
    public Flux<Budget> list(AccountScope scope, String afterName, String afterId, int limit) {
        Bson filter = Filters.eq("accountId", scope.accountId());
        if (afterName != null) {
            filter = Filters.and(filter, Filters.or(Filters.gt("sortName", afterName),
                    Filters.and(Filters.eq("sortName", afterName), Filters.gt("_id", afterId))));
        }
        return Flux.from(mongo.collection(COLLECTION).find(filter).sort(Sorts.ascending("sortName", "_id")).limit(limit + 1))
                .map(d -> mongo.read(d, Budget.class));
    }

    @Override
    public Flux<Budget> listAll(AccountScope scope) {
        return Flux.from(mongo.collection(COLLECTION).find(Filters.eq("accountId", scope.accountId())).sort(Sorts.ascending("sortName", "_id")))
                .map(d -> mongo.read(d, Budget.class));
    }

    @Override
    public Flux<Budget> listEverything() {
        return Flux.from(mongo.collection(COLLECTION).find().sort(Sorts.ascending("_id"))).map(d -> mongo.read(d, Budget.class));
    }

    @Override
    public Mono<Budget> insert(Budget budget) {
        return Mono.from(mongo.collection(COLLECTION).insertOne(document(budget))).thenReturn(budget)
                .onErrorResume(MongoException.class, e -> Mongo.duplicateKey(e)
                        ? Mono.error(new ConflictException("the Idempotency-Key was used"))
                        : Mono.error(e));
    }

    @Override
    public Mono<Boolean> replace(Budget budget, long expectedVersion) {
        return Mono.from(mongo.collection(COLLECTION).replaceOne(Filters.and(Filters.eq("_id", budget.budgetId()), Filters.eq("version", expectedVersion)),
                document(budget))).map(r -> r.getMatchedCount() == 1);
    }

    @Override
    public Mono<Boolean> delete(String accountId, String budgetId, Long expectedVersion) {
        Bson filter = Filters.and(Filters.eq("_id", budgetId), Filters.eq("accountId", accountId));
        if (expectedVersion != null) {
            filter = Filters.and(filter, Filters.eq("version", expectedVersion));
        }
        return Mono.from(mongo.collection(COLLECTION).deleteOne(filter)).map(r -> r.getDeletedCount() == 1);
    }

    @Override
    public Mono<Long> count(AccountScope scope) {
        return Mono.from(mongo.collection(COLLECTION).countDocuments(Filters.eq("accountId", scope.accountId())));
    }

    private Document document(Budget b) {
        Document d = new Document("_id", b.budgetId()).append("accountId", b.accountId()).append("sortName", b.name().toLowerCase(Locale.ROOT))
                .append("version", b.version()).append(Mongo.DOC, mongo.doc(b));
        if (b.idempotencyKey() != null) {
            d.append("idempotencyKey", b.idempotencyKey());
        }
        return d;
    }
}
