package com.iaf.console.state.inventory.adapters.mongo;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.bson.Document;
import org.bson.conversions.Bson;

import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
import com.iaf.console.kit.paging.Cursor;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.state.inventory.domain.Resource;
import com.iaf.console.state.inventory.domain.ResourceMatching;
import com.iaf.console.state.inventory.domain.ResourceQuery;
import com.iaf.console.state.inventory.domain.ResourceState;
import com.iaf.console.state.inventory.ports.outbound.ResourceProjectionPort;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The projection in MongoDB: collection {@code resources}, {@code _id} the wfrn, the resource under
 * {@code doc} and the filtered and sorted fields beside it ({@code accountId}, {@code region},
 * {@code type}, {@code name}, {@code state}, {@code live}, {@code version}, {@code registryId},
 * {@code tagsFlat} as {@code name=value}, {@code drifted}, {@code updatedAtNs}, {@code createdAtNs}).
 * A write replaces the document only when its version is newer. The shard key, when the
 * collection is sharded, is {@code {accountId, type, _id}}.
 */
public final class MongoResourceProjection implements ResourceProjectionPort, MongoIndexed {

    static final String COLLECTION = "resources";

    private final Mongo mongo;

    public MongoResourceProjection(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return mongo.indexes(COLLECTION, List.of(
                new IndexModel(Indexes.compoundIndex(Indexes.ascending("accountId", "region", "live"), Indexes.descending("updatedAtNs"),
                        Indexes.ascending("_id"))),
                new IndexModel(Indexes.compoundIndex(Indexes.ascending("accountId", "region", "type", "live"), Indexes.descending("updatedAtNs"),
                        Indexes.ascending("_id"))),
                new IndexModel(Indexes.ascending("accountId", "region", "live", "name", "_id")),
                new IndexModel(Indexes.compoundIndex(Indexes.ascending("accountId", "region", "live"), Indexes.descending("createdAtNs"),
                        Indexes.ascending("_id"))),
                new IndexModel(Indexes.ascending("accountId", "region", "tagsFlat")),
                new IndexModel(Indexes.ascending("registryId"),
                        new IndexOptions().partialFilterExpression(Filters.and(Filters.exists("registryId"), Filters.eq("live", true)))),
                new IndexModel(Indexes.ascending("state", "updatedAtNs")),
                new IndexModel(Indexes.ascending("expiresAt"), new IndexOptions().expireAfter(0L, TimeUnit.SECONDS))));
    }

    @Override
    public Mono<Boolean> upsert(Resource r) {
        Document d = new Document("_id", r.wfrn())
                .append("accountId", r.accountId())
                .append("region", r.region())
                .append("type", r.type())
                .append("name", r.name())
                .append("state", r.state().wire())
                .append("live", r.live())
                .append("version", r.version())
                .append("registryId", r.registryId())
                .append("tagsFlat", r.tags().entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList())
                .append("drifted", r.drift() != null)
                .append("updatedAtNs", Mongo.nanos(r.updatedAt()))
                .append("createdAtNs", Mongo.nanos(r.createdAt()))
                .append("expiresAt", r.deleted() ? Mongo.date(r.tombstoneUntil()) : null)
                .append(Mongo.DOC, mongo.doc(r));
        Bson older = Filters.and(Filters.eq("_id", r.wfrn()), Filters.lt("version", r.version()));
        return Mono.from(mongo.collection(COLLECTION).replaceOne(older, d, new ReplaceOptions().upsert(true)))
                .map(result -> result.getMatchedCount() > 0 || result.getUpsertedId() != null)
                .onErrorResume(MongoException.class, e -> Mongo.duplicateKey(e) ? Mono.just(false) : Mono.error(e));
    }

    @Override
    public Mono<Resource> find(AccountScope scope, String wfrn) {
        return Mono.from(mongo.collection(COLLECTION)
                .find(Filters.and(Filters.eq("_id", wfrn), Filters.eq("accountId", scope.accountId()), Filters.eq("region", scope.region())))
                .first())
                .map(d -> mongo.read(d, Resource.class));
    }

    @Override
    public Mono<Page<Resource>> list(AccountScope scope, ResourceQuery q) {
        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.eq("accountId", scope.accountId()));
        filters.add(Filters.eq("region", scope.region()));
        if (q.state() == null) {
            filters.add(Filters.eq("live", true));
        } else {
            filters.add(Filters.eq("state", q.state().wire()));
        }
        if (q.type() != null) {
            filters.add(Filters.eq("type", q.type()));
        }
        if (q.namePrefix() != null) {
            filters.add(Filters.regex("name", "^" + Pattern.quote(q.namePrefix())));
        }
        if (!q.tags().isEmpty()) {
            filters.add(Filters.all("tagsFlat", q.tags().entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList()));
        }
        if (q.wfrnPattern() != null) {
            filters.add(Filters.regex("_id", ResourceMatching.regexOf(q.wfrnPattern()).pattern()));
        }
        if (q.updatedAfter() != null) {
            filters.add(Filters.gt("updatedAtNs", Mongo.nanos(q.updatedAfter())));
        }
        if (q.drift() != null) {
            filters.add(Filters.eq("drifted", q.drift()));
        }
        String field = sortField(q.sort());
        boolean asc = q.sort().ascending();
        if (q.after() != null) {
            Object value = cursorValue(q.sort(), q.after());
            Bson beyond = asc ? Filters.gt(field, value) : Filters.lt(field, value);
            filters.add(Filters.or(beyond, Filters.and(Filters.eq(field, value), Filters.gt("_id", q.after().id()))));
        }
        Bson sort = asc ? Sorts.orderBy(Sorts.ascending(field), Sorts.ascending("_id")) : Sorts.orderBy(Sorts.descending(field), Sorts.ascending("_id"));
        return Flux.from(mongo.collection(COLLECTION).find(Filters.and(filters)).sort(sort).limit(q.limit() + 1))
                .map(d -> mongo.read(d, Resource.class))
                .filter(r -> ResourceMatching.matches(q, r))
                .collectList()
                .map(fetched -> Page.from(fetched, q.limit(), r -> ResourceMatching.cursorOf(q, r)));
    }

    private static String sortField(ResourceQuery.Sort sort) {
        return switch (sort.field()) {
            case "updatedAt" -> "updatedAtNs";
            case "createdAt" -> "createdAtNs";
            default -> "name";
        };
    }

    private static Object cursorValue(ResourceQuery.Sort sort, Cursor cursor) {
        return "name".equals(sort.field()) ? cursor.sortText() : cursor.sortLong();
    }

    @Override
    public Mono<Resource> findByRegistryId(String registryId) {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.and(Filters.eq("registryId", registryId), Filters.eq("live", true))).first())
                .map(d -> mongo.read(d, Resource.class));
    }

    @Override
    public Flux<Resource> listPendingBefore(Instant cutoff, int limit) {
        return Flux.from(mongo.collection(COLLECTION)
                .find(Filters.and(Filters.eq("state", ResourceState.PENDING.wire()), Filters.lt("updatedAtNs", Mongo.nanos(cutoff))))
                .sort(Sorts.ascending("updatedAtNs")).limit(limit))
                .map(d -> mongo.read(d, Resource.class));
    }

    @Override
    public Flux<Resource> listBusyBefore(Instant cutoff, int limit) {
        return Flux.from(mongo.collection(COLLECTION)
                .find(Filters.and(Filters.in("state", ResourceState.UPDATING.wire(), ResourceState.DELETING.wire()),
                        Filters.lt("updatedAtNs", Mongo.nanos(cutoff))))
                .sort(Sorts.ascending("updatedAtNs")).limit(limit))
                .map(d -> mongo.read(d, Resource.class));
    }

    @Override
    public Mono<Long> countLive() {
        return Mono.from(mongo.collection(COLLECTION).countDocuments(Filters.eq("live", true)));
    }

    /** The fields a document carries beside the record, for the README and the tests. */
    public static Map<String, String> indexedFields() {
        return Map.of("accountId", "the account", "region", "the region", "type", "the type", "name", "the name", "state", "the state",
                "live", "not a tombstone", "registryId", "the platform's id", "tagsFlat", "name=value", "drifted", "an open finding",
                "updatedAtNs", "epoch nanoseconds");
    }
}
