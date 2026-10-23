package com.iaf.console.identity.accessreviews.adapters.mongo;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.bson.conversions.Bson;

import com.iaf.console.identity.accessreviews.domain.AccessReviewItem;
import com.iaf.console.identity.accessreviews.ports.outbound.AccessReviewItemStore;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
import com.iaf.console.kit.paging.Cursor;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoBulkWriteException;
import com.mongodb.MongoException;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndReplaceOptions;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Collection {@code access_review_items}: {@code _id} the item id (a ULID, so id order is the order
 * they were made), with the account, review, user, decision and version beside the record.
 */
public final class MongoAccessReviewItemStore implements AccessReviewItemStore, MongoIndexed {

    static final String COLLECTION = "access_review_items";

    private final Mongo mongo;

    public MongoAccessReviewItemStore(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return mongo.indexes(COLLECTION, List.of(
                new IndexModel(Indexes.ascending("reviewId", "decision", "_id")),
                new IndexModel(Indexes.ascending("reviewId", "_id"))));
    }

    @Override
    public Mono<Void> insertAll(List<AccessReviewItem> items) {
        if (items.isEmpty()) {
            return Mono.empty();
        }
        List<Document> documents = items.stream().map(this::document).toList();
        return Mono.from(mongo.collection(COLLECTION).insertMany(documents)).then()
                .onErrorMap(MongoException.class, e -> duplicate(e) ? new ConflictException("an access review item id exists") : e);
    }

    @Override
    public Mono<AccessReviewItem> update(AccessReviewItem item, long expectedVersion) {
        Bson filter = Filters.and(Filters.eq("_id", item.itemId()), Filters.eq("accountId", item.accountId()), Filters.eq("version", expectedVersion));
        return Mono.from(mongo.collection(COLLECTION).findOneAndReplace(filter, document(item),
                new FindOneAndReplaceOptions().returnDocument(ReturnDocument.AFTER))).map(this::read);
    }

    @Override
    public Mono<AccessReviewItem> find(AccountScope scope, String reviewId, String itemId) {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.and(Filters.eq("_id", itemId), Filters.eq("accountId", scope.accountId()),
                Filters.eq("reviewId", reviewId))).first()).map(this::read);
    }

    @Override
    public Mono<Page<AccessReviewItem>> list(AccountScope scope, String reviewId, AccessReviewItem.Decision decision, int limit, String after) {
        Bson filter = of(scope, reviewId);
        if (decision != null) {
            filter = Filters.and(filter, Filters.eq("decision", decision.name()));
        }
        if (after != null) {
            filter = Filters.and(filter, Filters.gt("_id", CursorPaging.decode(after).id()));
        }
        return Flux.from(mongo.collection(COLLECTION).find(filter).sort(Sorts.ascending("_id")).limit(limit + 1)).map(this::read).collectList()
                .map(fetched -> Page.from(fetched, limit, i -> new Cursor(i.itemId(), i.itemId())));
    }

    @Override
    public Flux<AccessReviewItem> findPending(AccountScope scope, String reviewId) {
        return Flux.from(mongo.collection(COLLECTION).find(Filters.and(of(scope, reviewId), Filters.eq("decision", AccessReviewItem.Decision.pending.name())))
                .sort(Sorts.ascending("_id"))).map(this::read);
    }

    @Override
    public Mono<Map<AccessReviewItem.Decision, Long>> countByDecision(AccountScope scope, String reviewId) {
        return Flux.from(mongo.collection(COLLECTION).aggregate(List.of(Aggregates.match(of(scope, reviewId)),
                        Aggregates.group("$decision", Accumulators.sum("n", 1)))))
                .collect(() -> new EnumMap<AccessReviewItem.Decision, Long>(AccessReviewItem.Decision.class),
                        (counts, d) -> counts.put(AccessReviewItem.Decision.valueOf(d.getString("_id")), ((Number) d.get("n")).longValue()))
                .map(counts -> (Map<AccessReviewItem.Decision, Long>) counts);
    }

    @Override
    public Mono<Void> deleteByReview(AccountScope scope, String reviewId) {
        return Mono.from(mongo.collection(COLLECTION).deleteMany(of(scope, reviewId))).then();
    }

    private static Bson of(AccountScope scope, String reviewId) {
        return Filters.and(Filters.eq("reviewId", reviewId), Filters.eq("accountId", scope.accountId()));
    }

    private static boolean duplicate(MongoException e) {
        if (e instanceof MongoBulkWriteException bulk) {
            return bulk.getWriteErrors().stream().anyMatch(w -> ErrorCategory.fromErrorCode(w.getCode()) == ErrorCategory.DUPLICATE_KEY);
        }
        return Mongo.duplicateKey(e);
    }

    /** The record: {@code accountId} (write-only) and {@code version} (never served) come from the fields beside {@code doc}. */
    private AccessReviewItem read(Document d) {
        Document doc = d.get(Mongo.DOC, Document.class);
        doc.put("accountId", d.getString("accountId"));
        return mongo.as(doc, AccessReviewItem.class).atVersion(((Number) d.get("version")).longValue());
    }

    private Document document(AccessReviewItem i) {
        return new Document("_id", i.itemId())
                .append("accountId", i.accountId())
                .append("reviewId", i.reviewId())
                .append("userId", i.userId())
                .append("decision", i.decision().name())
                .append("version", i.version())
                .append(Mongo.DOC, mongo.doc(i));
    }
}
