package com.iaf.console.drift.drift.adapters.mongo;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.bson.Document;
import org.bson.conversions.Bson;

import com.iaf.console.drift.drift.domain.Tier;
import com.iaf.console.drift.drift.domain.Watch;
import com.iaf.console.drift.drift.ports.outbound.WatchStorePort;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
import com.iaf.console.kit.paging.Cursor;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The watch list in MongoDB, collection {@code drift_watches}: one document per managed resource,
 * keyed by wfrn, with its shard, tier and next read beside the JSON so a shard's due resources are
 * one indexed range read.
 */
public final class MongoWatchStore implements WatchStorePort, MongoIndexed {

    static final String COLLECTION = "drift_watches";

    private final Mongo mongo;

    public MongoWatchStore(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return mongo.indexes(COLLECTION, List.of(
                new IndexModel(Indexes.ascending("shard", "nextCheckAt")),
                new IndexModel(Indexes.ascending("shard", "tier", "nextCheckAt")),
                new IndexModel(Indexes.ascending("accountId", "type", "_id")),
                new IndexModel(Indexes.compoundIndex(Indexes.ascending("accountId"), Indexes.descending("lastCheckedAt"))),
                new IndexModel(Indexes.ascending("providerId")),
                new IndexModel(Indexes.ascending("registryId"))));
    }

    @Override
    public Mono<Watch> find(String wfrn) {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.eq("_id", wfrn)).first()).map(this::read);
    }

    @Override
    public Flux<Watch> findByWfrns(Collection<String> wfrns) {
        if (wfrns.isEmpty()) {
            return Flux.empty();
        }
        return Flux.from(mongo.collection(COLLECTION).find(Filters.in("_id", wfrns))).map(this::read);
    }

    @Override
    public Flux<Watch> findDue(int shard, Tier tier, Instant now, int limit) {
        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.eq("shard", shard));
        if (tier != null) {
            filters.add(Filters.eq("tier", tier.id()));
        }
        filters.add(Filters.lte("nextCheckAt", Mongo.date(now)));
        return Flux.from(mongo.collection(COLLECTION).find(Filters.and(filters)).sort(Sorts.ascending("nextCheckAt")).limit(limit)).map(this::read);
    }

    @Override
    public Flux<Watch> findByProviderId(String providerId, String type) {
        Bson byId = Filters.or(Filters.eq("providerId", providerId), Filters.eq("registryId", providerId));
        return Flux.from(mongo.collection(COLLECTION).find(type == null ? byId : Filters.and(byId, Filters.eq("type", type)))).map(this::read);
    }

    @Override
    public Flux<Watch> findByProviderIds(Collection<String> providerIds, String type) {
        if (providerIds.isEmpty()) {
            return Flux.empty();
        }
        Bson byIds = Filters.or(Filters.in("providerId", providerIds), Filters.in("registryId", providerIds));
        return Flux.from(mongo.collection(COLLECTION).find(type == null ? byIds : Filters.and(byIds, Filters.eq("type", type)))).map(this::read);
    }

    @Override
    public Flux<String> listAccounts() {
        return Flux.from(mongo.collection(COLLECTION).distinct("accountId", String.class));
    }

    @Override
    public Mono<Page<Watch>> list(AccountScope scope, String type, int limit, String after) {
        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.eq("accountId", scope.accountId()));
        if (type != null) {
            filters.add(Filters.eq("type", type));
        }
        if (after != null && !after.isBlank()) {
            filters.add(Filters.gt("_id", CursorPaging.decode(after).id()));
        }
        return Flux.from(mongo.collection(COLLECTION).find(Filters.and(filters)).sort(Sorts.ascending("_id")).limit(limit + 1))
                .map(this::read)
                .collectList()
                .map(fetched -> Page.from(fetched, limit, w -> new Cursor(w.wfrn(), w.wfrn())));
    }

    @Override
    public Mono<Instant> findLastCheckedAt(AccountScope scope) {
        return Mono.from(mongo.collection(COLLECTION)
                .find(Filters.and(Filters.eq("accountId", scope.accountId()), Filters.ne("lastCheckedAt", null)))
                .sort(Sorts.descending("lastCheckedAt"))
                .projection(Projections.include("lastCheckedAt"))
                .first())
                .mapNotNull(d -> Mongo.instant(d.getDate("lastCheckedAt")));
    }

    @Override
    public Mono<Watch> save(Watch watch) {
        Watch next = watch.withRev(watch.rev() + 1);
        if (watch.rev() == 0) {
            return Mono.from(mongo.collection(COLLECTION).insertOne(toDocument(next)))
                    .thenReturn(next)
                    .onErrorMap(MongoException.class, e -> Mongo.duplicateKey(e) ? new Conflict(watch.wfrn()) : e);
        }
        return Mono.from(mongo.collection(COLLECTION).replaceOne(Filters.and(Filters.eq("_id", watch.wfrn()), Filters.eq("rev", watch.rev())),
                        toDocument(next)))
                .flatMap(r -> r.getMatchedCount() == 1 ? Mono.just(next) : Mono.error(new Conflict(watch.wfrn())));
    }

    @Override
    public Mono<Void> delete(String wfrn) {
        return Mono.from(mongo.collection(COLLECTION).deleteOne(Filters.eq("_id", wfrn))).then();
    }

    @Override
    public Mono<Long> count() {
        return Mono.from(mongo.collection(COLLECTION).countDocuments());
    }

    private Document toDocument(Watch w) {
        return new Document("_id", w.wfrn())
                .append("rev", w.rev())
                .append("accountId", w.accountId())
                .append("region", w.region())
                .append("type", w.type())
                .append("shard", w.shard())
                .append("tier", w.tier().id())
                .append("nextCheckAt", Mongo.date(w.nextCheckAt() != null ? w.nextCheckAt() : Instant.EPOCH))
                .append("lastCheckedAt", Mongo.date(w.lastCheckedAt()))
                .append("providerId", w.providerId())
                .append("registryId", w.registryId())
                .append(Mongo.DOC, mongo.doc(w));
    }

    private Watch read(Document d) {
        return mongo.read(d, Watch.class);
    }
}
