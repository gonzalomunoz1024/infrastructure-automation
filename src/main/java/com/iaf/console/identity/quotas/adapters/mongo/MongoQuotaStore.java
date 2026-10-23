package com.iaf.console.identity.quotas.adapters.mongo;

import java.util.List;

import org.bson.Document;
import org.bson.conversions.Bson;

import com.iaf.console.identity.quotas.domain.QuotaOverride;
import com.iaf.console.identity.quotas.domain.QuotaRequest;
import com.iaf.console.identity.quotas.ports.outbound.QuotaStore;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
import com.iaf.console.kit.paging.Cursor;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndReplaceOptions;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Collections {@code quota_overrides} ({@code _id} account and key) and {@code quota_requests} ({@code _id} the request id). */
public final class MongoQuotaStore implements QuotaStore, MongoIndexed {

    static final String OVERRIDES = "quota_overrides";
    static final String REQUESTS = "quota_requests";

    private final Mongo mongo;

    public MongoQuotaStore(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return mongo.indexes(OVERRIDES, List.of(new IndexModel(Indexes.ascending("accountId", "key"))))
                .then(mongo.indexes(REQUESTS, List.of(new IndexModel(Indexes.ascending("accountId", "key", "status")),
                        new IndexModel(Indexes.ascending("accountId", "status", "_id")))));
    }

    @Override
    public Mono<QuotaOverride> upsert(QuotaOverride o) {
        Document d = new Document("_id", o.accountId() + ":" + o.key()).append("accountId", o.accountId()).append("key", o.key())
                .append(Mongo.DOC, mongo.doc(o));
        return Mono.from(mongo.collection(OVERRIDES).replaceOne(Filters.eq("_id", o.accountId() + ":" + o.key()), d, new ReplaceOptions().upsert(true)))
                .thenReturn(o);
    }

    @Override
    public Mono<QuotaOverride> findOverride(AccountScope scope, String key) {
        return Mono.from(mongo.collection(OVERRIDES).find(Filters.eq("_id", scope.accountId() + ":" + key)).first()).map(d -> mongo.read(d, QuotaOverride.class));
    }

    @Override
    public Flux<QuotaOverride> findOverrides(AccountScope scope) {
        return Flux.from(mongo.collection(OVERRIDES).find(Filters.eq("accountId", scope.accountId())).sort(Sorts.ascending("key")))
                .map(d -> mongo.read(d, QuotaOverride.class));
    }

    @Override
    public Mono<QuotaRequest> insertRequest(QuotaRequest r) {
        return Mono.from(mongo.collection(REQUESTS).insertOne(request(r))).thenReturn(r);
    }

    @Override
    public Mono<QuotaRequest> updateRequest(QuotaRequest r, long expectedVersion) {
        Bson filter = Filters.and(Filters.eq("_id", r.requestId()), Filters.eq("version", expectedVersion));
        return Mono.from(mongo.collection(REQUESTS).findOneAndReplace(filter, request(r), new FindOneAndReplaceOptions().returnDocument(ReturnDocument.AFTER)))
                .map(d -> mongo.read(d, QuotaRequest.class));
    }

    @Override
    public Mono<QuotaRequest> findRequest(AccountScope scope, String requestId) {
        return Mono.from(mongo.collection(REQUESTS).find(Filters.and(Filters.eq("_id", requestId), Filters.eq("accountId", scope.accountId()))).first())
                .map(d -> mongo.read(d, QuotaRequest.class));
    }

    @Override
    public Mono<QuotaRequest> findOpenRequest(AccountScope scope, String key) {
        return Mono.from(mongo.collection(REQUESTS).find(Filters.and(Filters.eq("accountId", scope.accountId()), Filters.eq("key", key),
                Filters.eq("status", QuotaRequest.Status.open.name()))).sort(Sorts.ascending("_id")).first()).map(d -> mongo.read(d, QuotaRequest.class));
    }

    @Override
    public Mono<Page<QuotaRequest>> listRequests(AccountScope scope, QuotaRequest.Status status, int limit, String after) {
        Bson filter = Filters.eq("accountId", scope.accountId());
        if (status != null) {
            filter = Filters.and(filter, Filters.eq("status", status.name()));
        }
        if (after != null) {
            filter = Filters.and(filter, Filters.gt("_id", CursorPaging.decode(after).id()));
        }
        return Flux.from(mongo.collection(REQUESTS).find(filter).sort(Sorts.ascending("_id")).limit(limit + 1)).map(d -> mongo.read(d, QuotaRequest.class))
                .collectList().map(fetched -> Page.from(fetched, limit, r -> new Cursor(r.requestId(), r.requestId())));
    }

    private Document request(QuotaRequest r) {
        return new Document("_id", r.requestId()).append("accountId", r.accountId()).append("key", r.key()).append("status", r.status().name())
                .append("version", r.version()).append(Mongo.DOC, mongo.doc(r));
    }
}
