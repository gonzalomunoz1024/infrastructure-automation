package com.iaf.console.drift.rightsizing.adapters.mongo;

import java.util.ArrayList;
import java.util.List;

import org.bson.Document;
import org.bson.conversions.Bson;

import com.iaf.console.drift.rightsizing.domain.Recommendation;
import com.iaf.console.drift.rightsizing.domain.RecommendationQuery;
import com.iaf.console.drift.rightsizing.domain.RecommendationStatus;
import com.iaf.console.drift.rightsizing.ports.outbound.RecommendationStorePort;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
import com.iaf.console.kit.paging.Cursor;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The recommendations in MongoDB, collection {@code drift_recommendations}: one document per
 * recommendation, keyed by its id, with the queried fields beside the JSON. A partial unique index on
 * {@code wfrn} where {@code active} keeps one current (open or dismissed) recommendation per resource
 * even when two passes race; the saving is kept as a number so a page with the largest saving first
 * is one indexed read.
 */
public final class MongoRecommendationStore implements RecommendationStorePort, MongoIndexed {

    static final String COLLECTION = "drift_recommendations";

    private final Mongo mongo;

    public MongoRecommendationStore(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return mongo.indexes(COLLECTION, List.of(
                new IndexModel(Indexes.ascending("wfrn"),
                        new IndexOptions().name("one_current_recommendation_per_resource").unique(true).partialFilterExpression(Filters.eq("active", true))),
                new IndexModel(Indexes.compoundIndex(Indexes.ascending("accountId", "status"), Indexes.descending("savingSort", "_id"))),
                new IndexModel(Indexes.ascending("active", "accountId"))));
    }

    @Override
    public Mono<Recommendation> find(AccountScope scope, String recommendationId) {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.and(Filters.eq("_id", recommendationId), Filters.eq("accountId", scope.accountId())))
                .first()).map(this::read);
    }

    @Override
    public Mono<Recommendation> findAny(String recommendationId) {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.eq("_id", recommendationId)).first()).map(this::read);
    }

    @Override
    public Mono<Page<Recommendation>> list(AccountScope scope, RecommendationQuery query) {
        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.eq("accountId", scope.accountId()));
        if (query.status() != null) {
            filters.add(Filters.eq("status", query.status().id()));
        }
        if (query.kind() != null) {
            filters.add(Filters.eq("kind", query.kind().id()));
        }
        if (query.type() != null) {
            filters.add(Filters.eq("type", query.type()));
        }
        if (query.wfrn() != null) {
            filters.add(Filters.eq("wfrn", query.wfrn()));
        }
        if (query.after() != null) {
            double saving = ((Number) query.after().sortValue()).doubleValue();
            filters.add(Filters.or(Filters.lt("savingSort", saving),
                    Filters.and(Filters.eq("savingSort", saving), Filters.lt("_id", query.after().id()))));
        }
        return Flux.from(mongo.collection(COLLECTION).find(Filters.and(filters)).sort(Sorts.descending("savingSort", "_id")).limit(query.limit() + 1))
                .map(this::read)
                .collectList()
                .map(fetched -> Page.from(fetched, query.limit(), r -> new Cursor(r.savingSort(), r.recommendationId())));
    }

    @Override
    public Flux<Recommendation> findOpen(AccountScope scope) {
        return Flux.from(mongo.collection(COLLECTION)
                .find(Filters.and(Filters.eq("accountId", scope.accountId()), Filters.eq("status", RecommendationStatus.OPEN.id())))
                .sort(Sorts.descending("savingSort", "_id"))).map(this::read);
    }

    @Override
    public Mono<Recommendation> findCurrentByWfrn(String wfrn) {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.and(Filters.eq("wfrn", wfrn), Filters.eq("active", true))).first()).map(this::read);
    }

    @Override
    public Flux<Recommendation> findCurrent(String accountId) {
        Bson filter = accountId == null ? Filters.eq("active", true) : Filters.and(Filters.eq("active", true), Filters.eq("accountId", accountId));
        return Flux.from(mongo.collection(COLLECTION).find(filter).sort(Sorts.ascending("_id"))).map(this::read);
    }

    @Override
    public Mono<Recommendation> save(Recommendation r) {
        Recommendation next = r.withRev(r.rev() + 1);
        if (r.rev() == 0) {
            return Mono.from(mongo.collection(COLLECTION).insertOne(toDocument(next)))
                    .thenReturn(next)
                    .onErrorMap(MongoException.class, e -> Mongo.duplicateKey(e) ? new Conflict(r.recommendationId()) : e);
        }
        return Mono.from(mongo.collection(COLLECTION).replaceOne(Filters.and(Filters.eq("_id", r.recommendationId()), Filters.eq("rev", r.rev())),
                        toDocument(next)))
                .flatMap(result -> result.getMatchedCount() == 1 ? Mono.just(next) : Mono.error(new Conflict(r.recommendationId())))
                .onErrorMap(MongoException.class, e -> Mongo.duplicateKey(e) ? new Conflict(r.recommendationId()) : e);
    }

    private Document toDocument(Recommendation r) {
        return new Document("_id", r.recommendationId())
                .append("rev", r.rev())
                .append("accountId", r.accountId())
                .append("wfrn", r.wfrn())
                .append("type", r.type())
                .append("kind", r.kind().id())
                .append("status", r.status().id())
                .append("active", r.active())
                .append("savingSort", r.savingSort())
                .append(Mongo.DOC, mongo.doc(r));
    }

    private Recommendation read(Document d) {
        return mongo.read(d, Recommendation.class).withRev(((Number) d.get("rev")).longValue());
    }
}
