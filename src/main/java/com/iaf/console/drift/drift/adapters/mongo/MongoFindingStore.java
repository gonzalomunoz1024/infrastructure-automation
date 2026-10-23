package com.iaf.console.drift.drift.adapters.mongo;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.bson.Document;
import org.bson.conversions.Bson;

import com.iaf.console.drift.drift.domain.Finding;
import com.iaf.console.drift.drift.domain.FindingQuery;
import com.iaf.console.drift.drift.domain.FindingStatus;
import com.iaf.console.drift.drift.domain.FindingsSummary;
import com.iaf.console.drift.drift.domain.RevertStatus;
import com.iaf.console.drift.drift.ports.outbound.FindingStorePort;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
import com.iaf.console.kit.paging.Cursor;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.wfrn.Wfrn;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Findings in MongoDB, collection {@code drift_findings}: the finding as JSON under {@code doc},
 * and the fields the queries filter and sort on beside it. Pages run newest first on
 * {@code (openedAt, _id)}. A write never goes back in version and a closed finding stays closed:
 * the write is conditional, and a stale one answers what is stored. A partial unique index keeps
 * one finding open per resource; opening a second answers the one already open.
 */
public final class MongoFindingStore implements FindingStorePort, MongoIndexed {

    static final String COLLECTION = "drift_findings";

    private final Mongo mongo;

    public MongoFindingStore(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return mongo.indexes(COLLECTION, List.of(
                new IndexModel(Indexes.compoundIndex(Indexes.ascending("accountId", "status"), Indexes.descending("openedAt", "_id"))),
                new IndexModel(Indexes.ascending("wfrn", "status")),
                new IndexModel(Indexes.ascending("wfrn"), new IndexOptions().name("one_open_finding_per_resource").unique(true)
                        .partialFilterExpression(Filters.eq("status", FindingStatus.OPEN.id()))),
                new IndexModel(Indexes.ascending("status", "revertStatus"))));
    }

    @Override
    public Mono<Finding> find(AccountScope scope, String findingId) {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.and(Filters.eq("_id", findingId), Filters.eq("accountId", scope.accountId()))).first())
                .map(this::read);
    }

    @Override
    public Mono<Finding> findAny(String findingId) {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.eq("_id", findingId)).first()).map(this::read);
    }

    @Override
    public Mono<Page<Finding>> list(AccountScope scope, FindingQuery q) {
        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.eq("accountId", scope.accountId()));
        if (q.region() != null) {
            filters.add(Filters.eq("region", q.region()));
        }
        if (FindingQuery.OPEN.equals(q.status())) {
            filters.add(Filters.eq("status", FindingStatus.OPEN.id()));
        } else if (FindingQuery.CLOSED.equals(q.status())) {
            filters.add(Filters.eq("status", FindingStatus.CLOSED.id()));
        }
        if (q.severity() != null) {
            filters.add(Filters.eq("severity", q.severity().id()));
        }
        if (q.type() != null) {
            filters.add(Filters.eq("type", q.type()));
        }
        if (q.openedAfter() != null) {
            filters.add(Filters.gt("openedAt", Mongo.date(q.openedAfter())));
        }
        if (q.wfrn() != null && !q.wfrnIsPattern()) {
            filters.add(Filters.eq("wfrn", q.wfrn()));
        }
        if (q.after() != null) {
            Instant at = Instant.parse(String.valueOf(q.after().sortValue()));
            filters.add(Filters.or(Filters.lt("openedAt", Mongo.date(at)),
                    Filters.and(Filters.eq("openedAt", Mongo.date(at)), Filters.lt("_id", q.after().id()))));
        }
        return Flux.from(mongo.collection(COLLECTION).find(Filters.and(filters)).sort(Sorts.descending("openedAt", "_id")))
                .map(this::read)
                .filter(f -> q.wfrn() == null || !q.wfrnIsPattern() || Wfrn.matches(q.wfrn(), f.wfrn()))
                .take(q.limit() + 1L)
                .collectList()
                .map(fetched -> Page.from(fetched, q.limit(), f -> new Cursor(f.openedAt().toString(), f.findingId())));
    }

    @Override
    public Mono<FindingsSummary> countOpen(AccountScope scope, String region) {
        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.eq("accountId", scope.accountId()));
        filters.add(Filters.eq("status", FindingStatus.OPEN.id()));
        if (region != null) {
            filters.add(Filters.eq("region", region));
        }
        return Flux.from(mongo.collection(COLLECTION).find(Filters.and(filters)).projection(Projections.include("type", "severity")))
                .collectList()
                .map(docs -> {
                    Map<String, Long> byType = new TreeMap<>();
                    Map<String, Long> bySeverity = new TreeMap<>();
                    for (Document d : docs) {
                        byType.merge(d.getString("type"), 1L, Long::sum);
                        bySeverity.merge(d.getString("severity"), 1L, Long::sum);
                    }
                    return new FindingsSummary(docs.size(), byType, bySeverity, null);
                });
    }

    @Override
    public Mono<Finding> findOpenByWfrn(String wfrn) {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.and(Filters.eq("wfrn", wfrn), Filters.eq("status", FindingStatus.OPEN.id()))).first())
                .map(this::read);
    }

    @Override
    public Flux<Finding> findReverting(int limit) {
        return Flux.from(mongo.collection(COLLECTION)
                .find(Filters.and(Filters.eq("status", FindingStatus.OPEN.id()), Filters.eq("revertStatus", RevertStatus.RUNNING.id())))
                .limit(limit))
                .map(this::read);
    }

    @Override
    public Mono<Finding> save(Finding finding) {
        Bson writable = Filters.and(Filters.eq("_id", finding.findingId()), Filters.lte("version", finding.version()),
                Filters.ne("status", FindingStatus.CLOSED.id()));
        return Mono.from(mongo.collection(COLLECTION).replaceOne(writable, toDocument(finding), new ReplaceOptions().upsert(true)))
                .thenReturn(finding)
                .onErrorResume(MongoException.class, e -> Mongo.duplicateKey(e)
                        ? findAny(finding.findingId()).switchIfEmpty(Mono.defer(() -> findOpenByWfrn(finding.wfrn())))
                        : Mono.error(e));
    }

    private Document toDocument(Finding f) {
        return new Document("_id", f.findingId())
                .append("accountId", f.accountId())
                .append("region", f.region())
                .append("wfrn", f.wfrn())
                .append("type", f.type())
                .append("status", f.status().id())
                .append("severity", f.severity().id())
                .append("version", f.version())
                .append("openedAt", Mongo.date(f.openedAt()))
                .append("revertStatus", f.revertStatus() == null ? null : f.revertStatus().id())
                .append(Mongo.DOC, mongo.doc(f));
    }

    private Finding read(Document d) {
        return mongo.read(d, Finding.class);
    }
}
