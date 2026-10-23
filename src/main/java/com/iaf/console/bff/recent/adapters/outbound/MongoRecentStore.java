package com.iaf.console.bff.recent.adapters.outbound;

import java.util.List;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.console.bff.recent.domain.RecentItem;
import com.iaf.console.bff.recent.ports.outbound.RecentStore;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
import com.iaf.console.kit.scope.AccountScope;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Recent pages in the {@code recent_pages} collection, one document per account, person and page
 * ({@code _id} {@code <account>:<user>:<kind>:<key>}), {@code accountId}, {@code userId} and
 * {@code visitedAt} beside the record: a visit replaces its page's document, then the person's
 * pages past the newest {@code max} are removed.
 */
@Component
@Profile("mongo")
public class MongoRecentStore implements RecentStore, MongoIndexed {

    static final String COLLECTION = "recent_pages";

    private final Mongo mongo;

    public MongoRecentStore(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return mongo.indexes(COLLECTION, List.of(
                new IndexModel(Indexes.compoundIndex(Indexes.ascending("accountId"), Indexes.ascending("userId"), Indexes.descending("visitedAt")))));
    }

    @Override
    public Mono<List<RecentItem>> list(AccountScope scope, int max) {
        return Flux.from(mongo.collection(COLLECTION).find(person(scope)).sort(Sorts.descending("visitedAt")).limit(max))
                .map(d -> mongo.read(d, RecentItem.class)).collectList();
    }

    @Override
    public Mono<Void> visit(AccountScope scope, RecentItem visit, int max) {
        String id = scope.accountId() + ":" + scope.principal() + ":" + visit.kind() + ":" + visit.key();
        Document d = new Document("_id", id)
                .append("accountId", scope.accountId())
                .append("userId", scope.principal())
                .append("visitedAt", Mongo.date(visit.visitedAt()))
                .append(Mongo.DOC, mongo.doc(visit));
        return Mono.from(mongo.collection(COLLECTION).replaceOne(Filters.eq("_id", id), d, new ReplaceOptions().upsert(true)))
                .thenMany(Flux.from(mongo.collection(COLLECTION).find(person(scope)).sort(Sorts.descending("visitedAt")).skip(max)
                        .projection(Projections.include("_id"))))
                .map(old -> old.get("_id"))
                .collectList()
                .flatMap(ids -> ids.isEmpty() ? Mono.empty() : Mono.from(mongo.collection(COLLECTION).deleteMany(Filters.in("_id", ids))).then());
    }

    private static Bson person(AccountScope scope) {
        return Filters.and(Filters.eq("accountId", scope.accountId()), Filters.eq("userId", scope.principal()));
    }
}
