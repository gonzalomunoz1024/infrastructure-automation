package com.iaf.console.drift.drift.adapters.mongo;

import java.util.ArrayList;
import java.util.List;

import org.bson.Document;
import org.bson.conversions.Bson;

import com.iaf.console.drift.drift.domain.DiscoveryRun;
import com.iaf.console.drift.drift.domain.UnmanagedObject;
import com.iaf.console.drift.drift.ports.outbound.DiscoveryStorePort;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
import com.iaf.console.kit.paging.Cursor;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Discovery in MongoDB: runs in {@code drift_discovery_runs}, unmanaged objects in
 * {@code drift_unmanaged}, keyed by account, type, region and provider id. The account rides
 * beside the JSON, which leaves it out, and so do the guessed owner and the account an object was
 * assigned to, which the owner filter reads.
 */
public final class MongoDiscoveryStore implements DiscoveryStorePort, MongoIndexed {

    static final String RUNS = "drift_discovery_runs";
    static final String UNMANAGED = "drift_unmanaged";

    private final Mongo mongo;

    public MongoDiscoveryStore(Mongo mongo) {
        this.mongo = mongo;
    }

    static String key(String accountId, String type, String region, String providerId) {
        return accountId + "|" + type + "|" + region + "|" + providerId;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return mongo.indexes(RUNS, List.of(new IndexModel(Indexes.ascending("accountId", "type", "region", "status"))))
                .then(mongo.indexes(UNMANAGED, List.of(new IndexModel(Indexes.ascending("accountId", "type", "region", "_id")),
                        new IndexModel(Indexes.ascending("accountId", "_id")),
                        new IndexModel(Indexes.ascending("providerId", "type", "region")))));
    }

    @Override
    public Mono<DiscoveryRun> findRun(AccountScope scope, String runId) {
        return Mono.from(mongo.collection(RUNS).find(Filters.and(Filters.eq("_id", runId), Filters.eq("accountId", scope.accountId()))).first())
                .map(this::readRun);
    }

    @Override
    public Mono<DiscoveryRun> findRunningRun(AccountScope scope, String type, String region) {
        return Mono.from(mongo.collection(RUNS).find(Filters.and(Filters.eq("accountId", scope.accountId()), Filters.eq("type", type),
                Filters.eq("region", region), Filters.eq("status", DiscoveryRun.RUNNING))).first()).map(this::readRun);
    }

    @Override
    public Mono<DiscoveryRun> saveRun(DiscoveryRun run) {
        Document d = new Document("_id", run.runId())
                .append("accountId", run.accountId())
                .append("type", run.type())
                .append("region", run.region())
                .append("status", run.status())
                .append("startedAt", Mongo.date(run.startedAt()))
                .append(Mongo.DOC, mongo.doc(run));
        return Mono.from(mongo.collection(RUNS).replaceOne(Filters.eq("_id", run.runId()), d, new ReplaceOptions().upsert(true))).thenReturn(run);
    }

    @Override
    public Mono<Page<UnmanagedObject>> listUnmanaged(AccountScope scope, String type, String region, String owner, int limit, String after) {
        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.eq("accountId", scope.accountId()));
        if (type != null) {
            filters.add(Filters.eq("type", type));
        }
        if (region != null) {
            filters.add(Filters.eq("region", region));
        }
        if (MINE.equals(owner)) {
            filters.add(Filters.or(Filters.eq("guessAccountId", scope.accountId()), Filters.eq("assignedTo", scope.accountId())));
        } else if (UNKNOWN.equals(owner)) {
            filters.add(Filters.eq("guessAccountId", null));
        }
        if (after != null && !after.isBlank()) {
            filters.add(Filters.gt("_id", CursorPaging.decode(after).id()));
        }
        return Flux.from(mongo.collection(UNMANAGED).find(Filters.and(filters)).sort(Sorts.ascending("_id")).limit(limit + 1))
                .collectList()
                .map(docs -> {
                    Page<Document> page = Page.from(docs, limit, d -> new Cursor(d.getString("_id"), d.getString("_id")));
                    return page.map(this::readUnmanaged);
                });
    }

    @Override
    public Mono<UnmanagedObject> findUnmanaged(AccountScope scope, String type, String region, String providerId) {
        return Mono.from(mongo.collection(UNMANAGED).find(Filters.eq("_id", key(scope.accountId(), type, region, providerId))).first())
                .map(this::readUnmanaged);
    }

    @Override
    public Mono<UnmanagedObject> saveUnmanaged(UnmanagedObject o) {
        String id = key(o.accountId(), o.type(), o.region(), o.providerId());
        Document d = new Document("_id", id)
                .append("accountId", o.accountId())
                .append("type", o.type())
                .append("region", o.region())
                .append("providerId", o.providerId())
                .append("guessAccountId", o.ownerGuess() == null ? null : o.ownerGuess().accountId())
                .append("assignedTo", o.assignment() == null ? null : o.assignment().accountId())
                .append("seenAt", Mongo.date(o.seenAt()))
                .append(Mongo.DOC, mongo.doc(o));
        return Mono.from(mongo.collection(UNMANAGED).replaceOne(Filters.eq("_id", id), d, new ReplaceOptions().upsert(true))).thenReturn(o);
    }

    @Override
    public Mono<Void> deleteUnmanaged(AccountScope scope, String type, String region, String providerId) {
        return Mono.from(mongo.collection(UNMANAGED).deleteOne(Filters.eq("_id", key(scope.accountId(), type, region, providerId)))).then();
    }

    @Override
    public Flux<UnmanagedObject> findCopies(String type, String region, String providerId) {
        return Flux.from(mongo.collection(UNMANAGED).find(Filters.and(Filters.eq("providerId", providerId), Filters.eq("type", type),
                Filters.eq("region", region)))).map(this::readUnmanaged);
    }

    private DiscoveryRun readRun(Document d) {
        return mongo.read(d, DiscoveryRun.class).forAccount(d.getString("accountId"));
    }

    private UnmanagedObject readUnmanaged(Document d) {
        return mongo.read(d, UnmanagedObject.class).forAccount(d.getString("accountId"));
    }
}
