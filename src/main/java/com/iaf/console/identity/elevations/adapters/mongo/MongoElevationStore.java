package com.iaf.console.identity.elevations.adapters.mongo;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import org.bson.Document;
import org.bson.conversions.Bson;

import com.iaf.console.identity.elevations.domain.Elevation;
import com.iaf.console.identity.elevations.ports.outbound.ElevationStore;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
import com.iaf.console.kit.paging.Cursor;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndReplaceOptions;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Collection {@code elevations}: {@code _id} the elevation id; the account, person, role, kind,
 * stored status, {@code requestedAt} and {@code until} beside the record, for the reads.
 */
public final class MongoElevationStore implements ElevationStore, MongoIndexed {

    static final String COLLECTION = "elevations";
    private static final List<String> OPEN = List.of(Elevation.Status.pending.name(), Elevation.Status.active.name());

    private final Mongo mongo;

    public MongoElevationStore(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return mongo.indexes(COLLECTION, List.of(
                new IndexModel(Indexes.ascending("accountId", "userId", "status")),
                new IndexModel(Indexes.ascending("accountId", "_id")),
                new IndexModel(Indexes.ascending("userId", "status")),
                new IndexModel(Indexes.ascending("status", "until")),
                new IndexModel(Indexes.ascending("status", "requestedAt"))));
    }

    @Override
    public Mono<Elevation> insert(Elevation e) {
        return Mono.from(mongo.collection(COLLECTION).insertOne(document(e))).thenReturn(e)
                .onErrorMap(MongoException.class, x -> Mongo.duplicateKey(x) ? new ConflictException("the elevation id exists") : x);
    }

    @Override
    public Mono<Elevation> update(Elevation e, long expectedVersion) {
        Bson filter = Filters.and(Filters.eq("_id", e.elevationId()), Filters.eq("version", expectedVersion));
        return Mono.from(mongo.collection(COLLECTION).findOneAndReplace(filter, document(e), new FindOneAndReplaceOptions().returnDocument(ReturnDocument.AFTER)))
                .map(this::read);
    }

    @Override
    public Mono<Elevation> find(AccountScope scope, String elevationId) {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.and(Filters.eq("_id", elevationId), Filters.eq("accountId", scope.accountId()))).first())
                .map(this::read);
    }

    @Override
    public Flux<Elevation> findOpen(AccountScope scope, String userId, String role, Elevation.Kind kind) {
        Bson filter = Filters.and(Filters.eq("accountId", scope.accountId()), Filters.eq("userId", userId), Filters.in("status", OPEN));
        if (role != null) {
            filter = Filters.and(filter, Filters.eq("role", role));
        }
        if (kind != null) {
            filter = Filters.and(filter, Filters.eq("kind", kind.wire()));
        }
        return Flux.from(mongo.collection(COLLECTION).find(filter).sort(Sorts.ascending("_id"))).map(this::read);
    }

    @Override
    public Flux<Elevation> findAllOpen(AccountScope scope) {
        return Flux.from(mongo.collection(COLLECTION).find(Filters.and(Filters.eq("accountId", scope.accountId()), Filters.in("status", OPEN)))
                .sort(Sorts.ascending("_id"))).map(this::read);
    }

    @Override
    public Mono<Page<Elevation>> list(AccountScope scope, String userId, Elevation.Status status, Elevation.Kind kind, int limit, String after) {
        Bson filter = Filters.eq("accountId", scope.accountId());
        if (userId != null) {
            filter = Filters.and(filter, Filters.eq("userId", userId));
        }
        if (status != null) {
            filter = Filters.and(filter, Filters.eq("status", status.name()));
        }
        if (kind != null) {
            filter = Filters.and(filter, Filters.eq("kind", kind.wire()));
        }
        if (after != null) {
            filter = Filters.and(filter, Filters.lt("_id", CursorPaging.decode(after).id()));
        }
        return Flux.from(mongo.collection(COLLECTION).find(filter).sort(Sorts.descending("_id")).limit(limit + 1)).map(this::read).collectList()
                .map(fetched -> Page.from(fetched, limit, e -> new Cursor(e.elevationId(), e.elevationId())));
    }

    @Override
    public Flux<Elevation> findActive() {
        return Flux.from(mongo.collection(COLLECTION).find(Filters.eq("status", Elevation.Status.active.name())).sort(Sorts.ascending("_id")))
                .map(this::read);
    }

    @Override
    public Flux<Elevation> findActiveByUser(String userId) {
        return Flux.from(mongo.collection(COLLECTION).find(Filters.and(Filters.eq("userId", userId), Filters.eq("status", Elevation.Status.active.name())))
                .sort(Sorts.ascending("_id"))).map(this::read);
    }

    @Override
    public Flux<Elevation> findDue(Instant now, Instant askedBefore) {
        Bson ended = Filters.and(Filters.eq("status", Elevation.Status.active.name()), Filters.lte("until", Date.from(now)));
        Bson unanswered = Filters.and(Filters.eq("status", Elevation.Status.pending.name()), Filters.lt("requestedAt", Date.from(askedBefore)));
        return Flux.from(mongo.collection(COLLECTION).find(Filters.or(ended, unanswered)).sort(Sorts.ascending("_id"))).map(this::read);
    }

    private Elevation read(Document d) {
        Elevation e = mongo.read(d, Elevation.class);
        return new Elevation(e.elevationId(), e.accountId(), e.kind(), e.user(), e.role(), e.reason(), e.ticket(), e.durationMinutes(), e.status(),
                e.requestedAt(), e.requestedBy(), e.decidedAt(), e.decidedBy(), e.comment(), e.startsAt(), e.until(), e.endedAt(), e.endedBy(), e.endComment(),
                e.approvers(), ((Number) d.get("version")).longValue());
    }

    private Document document(Elevation e) {
        Document d = new Document("_id", e.elevationId())
                .append("accountId", e.accountId())
                .append("userId", e.user().userId())
                .append("role", e.role())
                .append("kind", e.kind().wire())
                .append("status", e.status().name())
                .append("requestedAt", Date.from(e.requestedAt()))
                .append("version", e.version());
        if (e.until() != null) {
            d.append("until", Date.from(e.until()));
        }
        return d.append(Mongo.DOC, mongo.doc(e));
    }
}
