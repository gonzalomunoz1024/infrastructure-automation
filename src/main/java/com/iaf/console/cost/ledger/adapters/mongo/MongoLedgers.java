package com.iaf.console.cost.ledger.adapters.mongo;

import java.time.LocalDate;
import java.util.List;

import org.bson.Document;

import com.iaf.console.cost.ledger.domain.AccountLedger;
import com.iaf.console.cost.ledger.ports.outbound.LedgerStatePort;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
import com.iaf.console.kit.scope.AccountScope;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Each account's ledger position in MongoDB, as plain fields (not under {@code doc}), so marking a
 * day dirty is one atomic {@code $min} and {@code $inc}, and a close is a conditional update on
 * {@code rev}.
 */
public final class MongoLedgers implements LedgerStatePort, MongoIndexed {

    static final String COLLECTION = "cost_ledgers";

    private final Mongo mongo;

    public MongoLedgers(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return mongo.indexes(COLLECTION, List.<IndexModel>of());
    }

    @Override
    public Mono<AccountLedger> find(AccountScope scope) {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.eq("_id", scope.accountId())).first()).map(MongoLedgers::ledger);
    }

    @Override
    public Flux<AccountLedger> listAll() {
        return Flux.from(mongo.collection(COLLECTION).find().sort(Sorts.ascending("_id"))).map(MongoLedgers::ledger);
    }

    @Override
    public Mono<AccountLedger> createIfAbsent(AccountLedger l) {
        Document d = new Document("_id", l.accountId()).append("firstDay", l.firstDay().toString()).append("closedThrough", l.closedThrough().toString())
                .append("rev", l.rev());
        return Mono.from(mongo.collection(COLLECTION).insertOne(d)).thenReturn(l)
                .onErrorResume(MongoException.class, e -> Mongo.duplicateKey(e)
                        ? Mono.from(mongo.collection(COLLECTION).find(Filters.eq("_id", l.accountId())).first()).map(MongoLedgers::ledger)
                        : Mono.error(e));
    }

    @Override
    public Mono<Void> markDirty(String accountId, LocalDate day) {
        return Mono.from(mongo.collection(COLLECTION).updateOne(Filters.eq("_id", accountId),
                Updates.combine(Updates.min("dirtyFrom", day.toString()), Updates.inc("rev", 1L)), new UpdateOptions())).then();
    }

    @Override
    public Mono<Boolean> closed(String accountId, LocalDate closedThrough, long expectedRev) {
        return Mono.from(mongo.collection(COLLECTION).updateOne(Filters.and(Filters.eq("_id", accountId), Filters.eq("rev", expectedRev)),
                Updates.combine(Updates.set("closedThrough", closedThrough.toString()), Updates.unset("dirtyFrom"))))
                .map(r -> r.getMatchedCount() == 1);
    }

    private static AccountLedger ledger(Document d) {
        String dirty = d.getString("dirtyFrom");
        return new AccountLedger(d.getString("_id"), LocalDate.parse(d.getString("firstDay")), LocalDate.parse(d.getString("closedThrough")),
                dirty == null ? null : LocalDate.parse(dirty), ((Number) d.get("rev")).longValue());
    }
}
