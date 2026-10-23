package com.iaf.console.bff.digests.adapters.outbound;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.console.bff.digests.domain.Digest;
import com.iaf.console.bff.digests.domain.DigestSummary;
import com.iaf.console.bff.digests.ports.outbound.DigestStore;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
import com.iaf.console.kit.scope.AccountScope;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Digests in the {@code digests} collection, shared by every replica: one document per account and
 * week ({@code _id} {@code <account>:<weekStart>}, and a unique index on the two), first as a
 * claim ({@code status: claimed}, the owner and {@code leaseUntil}), then written
 * ({@code status: written} and the digest). A claim inserts the document, or takes it over once
 * its lease ran out; a save writes it only while it is not written.
 */
@Component
@Profile("mongo")
public class MongoDigestStore implements DigestStore, MongoIndexed {

    static final String COLLECTION = "digests";
    static final String CLAIMED = "claimed";
    static final String WRITTEN = "written";

    private final Mongo mongo;

    public MongoDigestStore(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return mongo.indexes(COLLECTION, List.of(
                new IndexModel(Indexes.compoundIndex(Indexes.ascending("accountId"), Indexes.descending("weekStart")), new IndexOptions().unique(true))));
    }

    @Override
    public Mono<Claim> claim(String accountId, LocalDate weekStart, String owner, Instant now, Duration lease) {
        String id = id(accountId, weekStart);
        Document claim = new Document("_id", id).append("accountId", accountId).append("weekStart", weekStart.toString()).append("status", CLAIMED)
                .append("owner", owner).append("leaseUntil", Mongo.date(now.plus(lease)));
        return Mono.from(mongo.collection(COLLECTION).insertOne(claim)).thenReturn(Claim.CLAIMED)
                .onErrorResume(MongoException.class, e -> Mongo.duplicateKey(e) ? takeOver(id, owner, now, lease) : Mono.error(e));
    }

    /** The claim of a week no one wrote, once its lease ran out (or it is this owner's own). */
    private Mono<Claim> takeOver(String id, String owner, Instant now, Duration lease) {
        Bson free = Filters.and(Filters.eq("_id", id), Filters.eq("status", CLAIMED),
                Filters.or(Filters.lt("leaseUntil", Mongo.date(now)), Filters.eq("owner", owner)));
        return Mono.from(mongo.collection(COLLECTION).updateOne(free, Updates.combine(Updates.set("owner", owner),
                Updates.set("leaseUntil", Mongo.date(now.plus(lease)))))).flatMap(result -> result.getMatchedCount() == 1 ? Mono.just(Claim.CLAIMED)
                        : Mono.from(mongo.collection(COLLECTION).find(Filters.eq("_id", id)).first())
                                .map(d -> WRITTEN.equals(d.getString("status")) ? Claim.WRITTEN : Claim.HELD)
                                .defaultIfEmpty(Claim.HELD));
    }

    @Override
    public Mono<Boolean> save(Digest digest, String owner) {
        Bson notWritten = Filters.and(Filters.eq("_id", id(digest.accountId(), digest.weekStart())), Filters.ne("status", WRITTEN));
        return Mono.from(mongo.collection(COLLECTION).updateOne(notWritten, Updates.combine(Updates.set("status", WRITTEN),
                Updates.set("generatedAt", Mongo.date(digest.generatedAt())), Updates.set(Mongo.DOC, mongo.doc(digest)), Updates.unset("leaseUntil"),
                Updates.set("owner", owner)))).map(result -> result.getModifiedCount() == 1);
    }

    @Override
    public Mono<Void> release(String accountId, LocalDate weekStart, String owner) {
        return Mono.from(mongo.collection(COLLECTION).deleteOne(Filters.and(Filters.eq("_id", id(accountId, weekStart)), Filters.eq("status", CLAIMED),
                Filters.eq("owner", owner)))).then();
    }

    @Override
    public Mono<Digest> find(AccountScope scope, LocalDate weekStart) {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.and(Filters.eq("_id", id(scope.accountId(), weekStart)), Filters.eq("status", WRITTEN)))
                .first()).map(d -> mongo.read(d, Digest.class));
    }

    @Override
    public Mono<List<DigestSummary>> list(AccountScope scope, int limit, LocalDate before) {
        List<Bson> filters = new ArrayList<>(List.of(Filters.eq("accountId", scope.accountId()), Filters.eq("status", WRITTEN)));
        if (before != null) {
            filters.add(Filters.lt("weekStart", before.toString()));
        }
        return Flux.from(mongo.collection(COLLECTION).find(Filters.and(filters)).sort(Sorts.descending("weekStart")).limit(limit))
                .map(d -> mongo.read(d, Digest.class).summary()).collectList();
    }

    private static String id(String accountId, LocalDate weekStart) {
        return accountId + ":" + weekStart;
    }
}
