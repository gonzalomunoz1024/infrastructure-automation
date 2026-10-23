package com.iaf.console.drift.rightsizing.adapters.mongo;

import java.time.Instant;
import java.util.List;

import org.bson.Document;

import com.iaf.console.drift.rightsizing.domain.ResourceUtilisation;
import com.iaf.console.drift.rightsizing.ports.outbound.UtilisationStorePort;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
import com.iaf.console.kit.scope.AccountScope;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;

import reactor.core.publisher.Mono;

/**
 * Each resource's last utilisation in MongoDB, collection {@code drift_utilisation}: one document
 * per resource, keyed by wfrn and replaced by each pass, with the account and the read time beside
 * the JSON for the summary's last pass.
 */
public final class MongoUtilisationStore implements UtilisationStorePort, MongoIndexed {

    static final String COLLECTION = "drift_utilisation";

    private final Mongo mongo;

    public MongoUtilisationStore(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return mongo.indexes(COLLECTION, List.of(new IndexModel(Indexes.compoundIndex(Indexes.ascending("accountId"), Indexes.descending("readAt")))));
    }

    @Override
    public Mono<ResourceUtilisation> find(String wfrn) {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.eq("_id", wfrn)).first()).map(d -> mongo.read(d, ResourceUtilisation.class));
    }

    @Override
    public Mono<ResourceUtilisation> save(ResourceUtilisation u) {
        Document doc = new Document("_id", u.wfrn())
                .append("accountId", u.accountId())
                .append("readAt", Mongo.date(u.readAt()))
                .append(Mongo.DOC, mongo.doc(u));
        return Mono.from(mongo.collection(COLLECTION).replaceOne(Filters.eq("_id", u.wfrn()), doc, new ReplaceOptions().upsert(true))).thenReturn(u);
    }

    @Override
    public Mono<Void> delete(String wfrn) {
        return Mono.from(mongo.collection(COLLECTION).deleteOne(Filters.eq("_id", wfrn))).then();
    }

    @Override
    public Mono<Instant> findLastReadAt(AccountScope scope) {
        return Mono.from(mongo.collection(COLLECTION)
                        .find(Filters.and(Filters.eq("accountId", scope.accountId()), Filters.ne("readAt", null)))
                        .sort(Sorts.descending("readAt"))
                        .projection(Projections.include("readAt"))
                        .first())
                .mapNotNull(d -> Mongo.instant(d.getDate("readAt")));
    }
}
