package com.iaf.console.identity.accessreviews.adapters.mongo;

import java.time.Instant;
import java.util.List;

import org.bson.Document;
import org.bson.conversions.Bson;

import com.iaf.console.identity.accessreviews.domain.AccessReview;
import com.iaf.console.identity.accessreviews.ports.outbound.AccessReviewStore;
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
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Collection {@code access_reviews}: {@code _id} the review id, with the account, status, start and
 * due times (epoch nanoseconds) and version beside the record. An open campaign also carries
 * {@code openAccountId}, unique and sparse, so a second open campaign of an account is a duplicate
 * key however the two starts interleave.
 */
public final class MongoAccessReviewStore implements AccessReviewStore, MongoIndexed {

    static final String COLLECTION = "access_reviews";
    static final String OPEN = "openAccountId";
    private static final Bson NEWEST_FIRST = Sorts.descending("startedAt", "_id");

    private final Mongo mongo;

    public MongoAccessReviewStore(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return mongo.indexes(COLLECTION, List.of(
                new IndexModel(Indexes.ascending(OPEN), new IndexOptions().unique(true).sparse(true)),
                new IndexModel(Indexes.ascending("accountId", "status")),
                new IndexModel(Indexes.ascending("accountId", "startedAt", "_id")),
                new IndexModel(Indexes.ascending("status", "dueAt"))));
    }

    @Override
    public Mono<AccessReview> insert(AccessReview r) {
        return Mono.from(mongo.collection(COLLECTION).insertOne(document(r))).thenReturn(r)
                .onErrorMap(MongoException.class, e -> Mongo.duplicateKey(e) ? new ConflictException("an access review of the account is open, or the id exists") : e);
    }

    @Override
    public Mono<AccessReview> update(AccessReview r, long expectedVersion) {
        Bson filter = Filters.and(Filters.eq("_id", r.reviewId()), Filters.eq("accountId", r.accountId()), Filters.eq("version", expectedVersion));
        return Mono.from(mongo.collection(COLLECTION).findOneAndReplace(filter, document(r), new FindOneAndReplaceOptions().returnDocument(ReturnDocument.AFTER)))
                .map(this::read)
                .onErrorMap(MongoException.class, e -> Mongo.duplicateKey(e) ? new ConflictException("an access review of the account is open") : e);
    }

    @Override
    public Mono<AccessReview> find(AccountScope scope, String reviewId) {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.and(Filters.eq("_id", reviewId), Filters.eq("accountId", scope.accountId()))).first())
                .map(this::read);
    }

    @Override
    public Mono<AccessReview> findOpen(AccountScope scope) {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.and(Filters.eq("accountId", scope.accountId()),
                Filters.eq("status", AccessReview.Status.open.name()))).first()).map(this::read);
    }

    @Override
    public Mono<AccessReview> findLatest(AccountScope scope) {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.eq("accountId", scope.accountId())).sort(NEWEST_FIRST).first()).map(this::read);
    }

    @Override
    public Mono<Page<AccessReview>> list(AccountScope scope, int limit, String after) {
        Bson filter = Filters.eq("accountId", scope.accountId());
        if (after != null) {
            Cursor cursor = CursorPaging.decode(after);
            long started = cursor.sortLong();
            filter = Filters.and(filter, Filters.or(Filters.lt("startedAt", started),
                    Filters.and(Filters.eq("startedAt", started), Filters.lt("_id", cursor.id()))));
        }
        return Flux.from(mongo.collection(COLLECTION).find(filter).sort(NEWEST_FIRST).limit(limit + 1)).map(this::read).collectList()
                .map(fetched -> Page.from(fetched, limit, r -> new Cursor(Mongo.nanos(r.startedAt()), r.reviewId())));
    }

    @Override
    public Flux<AccessReview> findDue(Instant now) {
        return Flux.from(mongo.collection(COLLECTION).find(Filters.and(Filters.eq("status", AccessReview.Status.open.name()),
                Filters.lte("dueAt", Mongo.nanos(now)))).sort(Sorts.ascending("dueAt", "_id"))).map(this::read);
    }

    /** The record: {@code accountId} is write-only on it (the API never shows it), so it comes from the field beside {@code doc}. */
    private AccessReview read(Document d) {
        Document doc = d.get(Mongo.DOC, Document.class);
        doc.put("accountId", d.getString("accountId"));
        return mongo.as(doc, AccessReview.class);
    }

    private Document document(AccessReview r) {
        Document d = new Document("_id", r.reviewId())
                .append("accountId", r.accountId())
                .append("status", r.status().name())
                .append("startedAt", Mongo.nanos(r.startedAt()))
                .append("dueAt", Mongo.nanos(r.dueAt()))
                .append("version", r.version());
        if (r.isOpen()) {
            d.append(OPEN, r.accountId());
        }
        return d.append(Mongo.DOC, mongo.doc(r));
    }
}
