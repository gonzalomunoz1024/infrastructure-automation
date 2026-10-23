package com.iaf.console.search.index.adapters.mongo;

import java.util.List;

import org.bson.Document;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
import com.iaf.console.search.index.domain.RebuildRun;
import com.iaf.console.search.index.ports.outbound.RebuildRunPort;

import reactor.core.publisher.Mono;

/** The rebuild runs in MongoDB: one document per run, the record under {@code doc}, the queried fields beside it. */
public final class MongoRebuildRuns implements RebuildRunPort, MongoIndexed {

    static final String COLLECTION = "rebuild_runs";

    private final Mongo mongo;

    public MongoRebuildRuns(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return mongo.indexes(COLLECTION, List.of(
                new IndexModel(Indexes.ascending("status", "startedAt")),
                new IndexModel(Indexes.ascending("idempotencyKey"), new IndexOptions().unique(true).sparse(true))));
    }

    @Override
    public Mono<RebuildRun> save(RebuildRun run) {
        Document d = new Document("_id", run.runId())
                .append("status", run.status().wire())
                .append("startedAt", Mongo.date(run.startedAt()))
                .append(Mongo.DOC, mongo.doc(run));
        if (run.idempotencyKey() != null) {
            d.append("idempotencyKey", run.idempotencyKey());
        }
        return Mono.from(mongo.collection(COLLECTION).replaceOne(Filters.eq("_id", run.runId()), d, new ReplaceOptions().upsert(true))).thenReturn(run);
    }

    @Override
    public Mono<RebuildRun> find(String runId) {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.eq("_id", runId)).first()).map(this::read);
    }

    @Override
    public Mono<RebuildRun> findRunning() {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.eq("status", RebuildRun.Status.RUNNING.wire())).sort(Sorts.ascending("startedAt")).first())
                .map(this::read);
    }

    @Override
    public Mono<RebuildRun> findByIdempotencyKey(String key) {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.eq("idempotencyKey", key)).first()).map(this::read);
    }

    private RebuildRun read(Document stored) {
        RebuildRun run = mongo.read(stored, RebuildRun.class);
        String key = stored.getString("idempotencyKey");
        return new RebuildRun(run.runId(), run.accountId(), run.status(), run.startedAt(), run.endedAt(), run.indexed(), run.error(), key);
    }
}
