package com.iaf.console.drift.scorecards.adapters.mongo;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.bson.Document;
import org.bson.conversions.Bson;

import com.iaf.console.drift.scorecards.domain.ResourceEvaluation;
import com.iaf.console.drift.scorecards.domain.RuleKey;
import com.iaf.console.drift.scorecards.domain.RuleResult;
import com.iaf.console.drift.scorecards.domain.RuleStatus;
import com.iaf.console.drift.scorecards.domain.Views;
import com.iaf.console.drift.scorecards.ports.outbound.EvaluationStorePort;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
import com.iaf.console.kit.paging.Cursor;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndReplaceOptions;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Evaluations in MongoDB. {@code scorecard_evaluations} holds one document per resource (the
 * evaluation as JSON under {@code doc}, its version, account and type beside it); a write is
 * conditional on the version before it. {@code scorecard_failures} holds one document per failing
 * resource and rule, kept from the evaluation's writes, so a rule's failing resources page in
 * order of {@code since} on an index however many resources the account has.
 */
public final class MongoEvaluationStore implements EvaluationStorePort, MongoIndexed {

    static final String EVALUATIONS = "scorecard_evaluations";
    static final String FAILURES = "scorecard_failures";

    private final Mongo mongo;

    public MongoEvaluationStore(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return mongo.indexes(EVALUATIONS, List.of(new IndexModel(Indexes.ascending("accountId", "_id"))))
                .then(mongo.indexes(FAILURES, List.of(
                        new IndexModel(Indexes.ascending("accountId", "rule", "since", "_id")),
                        new IndexModel(Indexes.ascending("accountId", "rule", "type", "since", "_id")),
                        new IndexModel(Indexes.ascending("wfrn")))));
    }

    @Override
    public Mono<ResourceEvaluation> find(AccountScope scope, String wfrn) {
        return Mono.from(mongo.collection(EVALUATIONS).find(Filters.and(Filters.eq("_id", wfrn), Filters.eq("accountId", scope.accountId()))).first())
                .map(this::read);
    }

    @Override
    public Mono<ResourceEvaluation> findByWfrn(String wfrn) {
        return Mono.from(mongo.collection(EVALUATIONS).find(Filters.eq("_id", wfrn)).first()).map(this::read);
    }

    @Override
    public Mono<Boolean> save(ResourceEvaluation e) {
        Document doc = toDocument(e);
        Mono<java.util.Optional<ResourceEvaluation>> written;
        if (e.version() == 1) {
            written = Mono.from(mongo.collection(EVALUATIONS).insertOne(doc)).thenReturn(java.util.Optional.<ResourceEvaluation>empty())
                    .onErrorResume(MongoException.class, ex -> Mongo.duplicateKey(ex) ? Mono.empty() : Mono.error(ex));
        } else {
            written = Mono.from(mongo.collection(EVALUATIONS).findOneAndReplace(
                            Filters.and(Filters.eq("_id", e.wfrn()), Filters.eq("version", e.version() - 1)), doc,
                            new FindOneAndReplaceOptions().returnDocument(ReturnDocument.BEFORE)))
                    .map(before -> java.util.Optional.of(read(before)));
        }
        return written.flatMap(before -> failures(before.orElse(null), e).thenReturn(true)).defaultIfEmpty(false);
    }

    /** Keeps the failures collection in step with an evaluation's change. */
    private Mono<Void> failures(ResourceEvaluation before, ResourceEvaluation after) {
        Set<String> now = after.results().stream().filter(r -> r.status() == RuleStatus.FAIL).map(r -> r.key().text()).collect(Collectors.toSet());
        List<String> gone = before == null ? List.of()
                : before.failing().stream().filter(k -> !now.contains(k)).map(k -> failureId(after.wfrn(), k)).toList();
        Mono<Void> removed = gone.isEmpty() ? Mono.empty() : Mono.from(mongo.collection(FAILURES).deleteMany(Filters.in("_id", gone))).then();
        List<Mono<Void>> upserts = new ArrayList<>();
        for (RuleResult r : after.results()) {
            if (r.status() != RuleStatus.FAIL) {
                continue;
            }
            RuleResult old = before == null ? null : before.result(r.key());
            if (r.sameAs(old) && old.since().equals(r.since())) {
                continue;
            }
            Views.Evaluation view = new Views.Evaluation(after.wfrn(), after.accountId(), after.region(), after.type(), after.name(), r.pack(), r.ruleId(),
                    r.severity(), r.status(), r.message(), r.since(), after.evaluatedAt());
            Document d = new Document("_id", failureId(after.wfrn(), r.key().text()))
                    .append("accountId", after.accountId())
                    .append("wfrn", after.wfrn())
                    .append("rule", r.key().text())
                    .append("type", after.type())
                    .append("since", Mongo.date(r.since()))
                    .append(Mongo.DOC, mongo.doc(view));
            upserts.add(Mono.from(mongo.collection(FAILURES).replaceOne(Filters.eq("_id", d.getString("_id")), d, new ReplaceOptions().upsert(true)))
                    .then());
        }
        return removed.then(Flux.concat(upserts).then());
    }

    private static String failureId(String wfrn, String key) {
        return wfrn + "|" + key;
    }

    @Override
    public Mono<ResourceEvaluation> delete(String wfrn) {
        return Mono.from(mongo.collection(EVALUATIONS).findOneAndDelete(Filters.eq("_id", wfrn)))
                .map(this::read)
                .flatMap(gone -> Mono.from(mongo.collection(FAILURES).deleteMany(Filters.eq("wfrn", wfrn))).thenReturn(gone));
    }

    @Override
    public Mono<Page<Views.Evaluation>> listFailing(AccountScope scope, RuleKey rule, String type, int limit, Cursor after) {
        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.eq("accountId", scope.accountId()));
        filters.add(Filters.eq("rule", rule.text()));
        if (type != null) {
            filters.add(Filters.eq("type", type));
        }
        if (after != null) {
            Instant at = Instant.parse(after.sortText());
            filters.add(Filters.or(Filters.gt("since", Mongo.date(at)), Filters.and(Filters.eq("since", Mongo.date(at)), Filters.gt("_id", after.id()))));
        }
        return Flux.from(mongo.collection(FAILURES).find(Filters.and(filters)).sort(Sorts.ascending("since", "_id")).limit(limit + 1))
                .map(d -> new Object[] {mongo.read(d, Views.Evaluation.class), d.getString("_id")})
                .collectList()
                .map(rows -> {
                    Page<Object[]> page = Page.from(rows, limit, row -> new Cursor(((Views.Evaluation) row[0]).since().toString(), (String) row[1]));
                    return page.map(row -> (Views.Evaluation) row[0]);
                });
    }

    @Override
    public Flux<ResourceEvaluation> listAll(AccountScope scope) {
        return Flux.from(mongo.collection(EVALUATIONS).find(Filters.eq("accountId", scope.accountId()))).map(this::read);
    }

    @Override
    public Flux<String> listAccounts() {
        return Flux.from(mongo.collection(EVALUATIONS).distinct("accountId", String.class));
    }

    private Document toDocument(ResourceEvaluation e) {
        return new Document("_id", e.wfrn())
                .append("accountId", e.accountId())
                .append("type", e.type())
                .append("version", e.version())
                .append("failing", e.failing())
                .append(Mongo.DOC, mongo.doc(e));
    }

    private ResourceEvaluation read(Document d) {
        return mongo.read(d, ResourceEvaluation.class);
    }
}
