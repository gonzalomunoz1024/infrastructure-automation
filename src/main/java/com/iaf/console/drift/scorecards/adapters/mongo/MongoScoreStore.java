package com.iaf.console.drift.scorecards.adapters.mongo;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import org.bson.Document;

import com.iaf.console.drift.scorecards.domain.CountDelta;
import com.iaf.console.drift.scorecards.domain.Counts;
import com.iaf.console.drift.scorecards.domain.RuleKey;
import com.iaf.console.drift.scorecards.domain.ScoreSnapshot;
import com.iaf.console.drift.scorecards.ports.outbound.ScoreStorePort;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
import com.iaf.console.kit.scope.AccountScope;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import com.mongodb.client.model.WriteModel;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Counters and snapshots in MongoDB. {@code scorecard_counters}: one document per account and rule
 * ({@code _id} {@code <accountId>|<pack>/<rule>}), moved with {@code $inc} and read clamped at
 * zero; the daily recount replaces them. {@code scorecard_snapshots}: one per account and day
 * ({@code _id} {@code <accountId>|<day>}), expired after 400 days.
 */
public final class MongoScoreStore implements ScoreStorePort, MongoIndexed {

    static final String COUNTERS = "scorecard_counters";
    static final String SNAPSHOTS = "scorecard_snapshots";
    static final Duration SNAPSHOT_RETENTION = Duration.ofDays(400);

    private final Mongo mongo;

    public MongoScoreStore(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return mongo.indexes(COUNTERS, List.of(new IndexModel(Indexes.ascending("accountId"))))
                .then(mongo.indexes(SNAPSHOTS, List.of(new IndexModel(Indexes.ascending("accountId", "day")),
                        new IndexModel(Indexes.ascending("at"), new IndexOptions().expireAfter(SNAPSHOT_RETENTION.toDays(), TimeUnit.DAYS)))));
    }

    @Override
    public Mono<Void> apply(String accountId, List<CountDelta> deltas) {
        if (deltas.isEmpty()) {
            return Mono.empty();
        }
        List<WriteModel<Document>> writes = new ArrayList<>();
        for (CountDelta d : deltas) {
            writes.add(new UpdateOneModel<>(Filters.eq("_id", accountId + "|" + d.key().text()),
                    Updates.combine(Updates.setOnInsert("accountId", accountId), Updates.setOnInsert("rule", d.key().text()),
                            Updates.inc("passed", d.passed()), Updates.inc("failed", d.failed())),
                    new UpdateOptions().upsert(true)));
        }
        return Mono.from(mongo.collection(COUNTERS).bulkWrite(writes)).then();
    }

    @Override
    public Mono<Void> replace(String accountId, Map<RuleKey, Counts> counts) {
        List<Document> docs = new ArrayList<>();
        counts.forEach((key, c) -> docs.add(new Document("_id", accountId + "|" + key.text()).append("accountId", accountId).append("rule", key.text())
                .append("passed", c.passed()).append("failed", c.failed())));
        Mono<Void> cleared = Mono.from(mongo.collection(COUNTERS).deleteMany(Filters.eq("accountId", accountId))).then();
        return docs.isEmpty() ? cleared : cleared.then(Mono.from(mongo.collection(COUNTERS).insertMany(docs)).then());
    }

    @Override
    public Mono<Map<RuleKey, Counts>> countsOf(AccountScope scope) {
        return Flux.from(mongo.collection(COUNTERS).find(Filters.eq("accountId", scope.accountId()))).collectList().map(MongoScoreStore::counts);
    }

    @Override
    public Mono<Map<String, Map<RuleKey, Counts>>> countsOfAccounts(Collection<String> accountIds) {
        return Flux.from(mongo.collection(COUNTERS).find(Filters.in("accountId", accountIds))).collectList().map(docs -> {
            Map<String, List<Document>> byAccount = new LinkedHashMap<>();
            docs.forEach(d -> byAccount.computeIfAbsent(d.getString("accountId"), k -> new ArrayList<>()).add(d));
            Map<String, Map<RuleKey, Counts>> out = new LinkedHashMap<>();
            byAccount.forEach((a, list) -> out.put(a, counts(list)));
            return out;
        });
    }

    private static Map<RuleKey, Counts> counts(List<Document> docs) {
        Map<RuleKey, Counts> out = new TreeMap<>();
        for (Document d : docs) {
            out.put(RuleKey.parse(d.getString("rule")), Counts.ZERO.plus(number(d, "passed"), number(d, "failed")));
        }
        return out;
    }

    private static long number(Document d, String field) {
        Object v = d.get(field);
        return v instanceof Number n ? n.longValue() : 0;
    }

    @Override
    public Flux<String> listAccounts() {
        return Flux.from(mongo.collection(COUNTERS).distinct("accountId", String.class));
    }

    @Override
    public Mono<Void> saveSnapshot(ScoreSnapshot s) {
        Document d = new Document("_id", s.accountId() + "|" + s.day())
                .append("accountId", s.accountId())
                .append("day", s.day().toString())
                .append("at", Mongo.date(Instant.now()))
                .append(Mongo.DOC, mongo.doc(s));
        return Mono.from(mongo.collection(SNAPSHOTS).replaceOne(Filters.eq("_id", d.getString("_id")), d, new ReplaceOptions().upsert(true))).then();
    }

    @Override
    public Flux<ScoreSnapshot> listSnapshots(AccountScope scope, LocalDate from) {
        return Flux.from(mongo.collection(SNAPSHOTS).find(Filters.and(Filters.eq("accountId", scope.accountId()), Filters.gte("day", from.toString())))
                .sort(Sorts.ascending("day"))).map(d -> mongo.read(d, ScoreSnapshot.class));
    }

    @Override
    public Mono<ScoreSnapshot> findLatestSnapshot(String accountId) {
        return Mono.from(mongo.collection(SNAPSHOTS).find(Filters.eq("accountId", accountId)).sort(Sorts.descending("day")).first())
                .map(d -> mongo.read(d, ScoreSnapshot.class));
    }
}
