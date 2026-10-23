package com.iaf.console.state.trail.adapters.mongo;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bson.Document;

import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.state.trail.domain.Digest;
import com.iaf.console.state.trail.ports.outbound.TrailDigestPort;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The digests in MongoDB: collection {@code trail_digests} ({@code _id} the digest's id, never
 * overwritten), {@code trail_keys} (the public JWKs, {@code _id} the key id) and the digester's
 * progress in {@code trail_progress}.
 */
public final class MongoTrailDigests implements TrailDigestPort, MongoIndexed {

    static final String DIGESTS = "trail_digests";
    static final String KEYS = "trail_keys";
    static final String PROGRESS = "trail_progress";
    private static final String DIGESTER = "digester";

    private final Mongo mongo;

    public MongoTrailDigests(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return mongo.indexes(DIGESTS, List.of(new IndexModel(Indexes.ascending("accountId", "region", "fromNs"))));
    }

    @Override
    public Mono<Boolean> save(Digest digest) {
        Document d = new Document("_id", digest.id())
                .append("accountId", digest.accountId())
                .append("region", digest.region())
                .append("fromNs", Mongo.nanos(digest.from()))
                .append("toNs", Mongo.nanos(digest.to()))
                .append(Mongo.DOC, mongo.doc(digest));
        return Mono.from(mongo.collection(DIGESTS).insertOne(d)).thenReturn(true)
                .onErrorResume(MongoException.class, e -> Mongo.duplicateKey(e) ? Mono.just(false) : Mono.error(e));
    }

    @Override
    public Mono<Digest> findLatestBefore(AccountScope scope, Instant at) {
        return Mono.from(mongo.collection(DIGESTS)
                .find(Filters.and(Filters.eq("accountId", scope.accountId()), Filters.eq("region", scope.region()), Filters.lte("toNs", Mongo.nanos(at))))
                .sort(Sorts.descending("fromNs")).first())
                .map(d -> mongo.read(d, Digest.class));
    }

    @Override
    public Flux<Digest> listDigests(AccountScope scope, Instant from, Instant to) {
        var filters = new java.util.ArrayList<org.bson.conversions.Bson>();
        filters.add(Filters.eq("accountId", scope.accountId()));
        filters.add(Filters.eq("region", scope.region()));
        if (to != null) {
            filters.add(Filters.lt("fromNs", Mongo.nanos(to)));
        }
        if (from != null) {
            filters.add(Filters.gt("toNs", Mongo.nanos(from)));
        }
        return Flux.from(mongo.collection(DIGESTS).find(Filters.and(filters)).sort(Sorts.ascending("fromNs"))).map(d -> mongo.read(d, Digest.class));
    }

    @Override
    public Mono<Instant> findSealedThrough() {
        return Mono.from(mongo.collection(PROGRESS).find(Filters.eq("_id", DIGESTER)).first())
                .flatMap(d -> Mono.justOrEmpty(d.getLong("throughNs")))
                .map(Mongo::fromNanos);
    }

    @Override
    public Mono<Boolean> advanceSealedThrough(Instant expected, Instant next) {
        if (expected == null) {
            return Mono.from(mongo.collection(PROGRESS).insertOne(new Document("_id", DIGESTER).append("throughNs", Mongo.nanos(next))))
                    .thenReturn(true)
                    .onErrorResume(MongoException.class, e -> Mongo.duplicateKey(e) ? Mono.just(false) : Mono.error(e));
        }
        return Mono.from(mongo.collection(PROGRESS).updateOne(Filters.and(Filters.eq("_id", DIGESTER), Filters.eq("throughNs", Mongo.nanos(expected))),
                Updates.set("throughNs", Mongo.nanos(next))))
                .map(result -> result.getModifiedCount() > 0);
    }

    @Override
    public Mono<Void> saveKey(Map<String, Object> publicJwk) {
        Document d = new Document(new LinkedHashMap<>(publicJwk)).append("_id", String.valueOf(publicJwk.get("kid")));
        return Mono.from(mongo.collection(KEYS).insertOne(d)).then()
                .onErrorResume(MongoException.class, e -> Mongo.duplicateKey(e) ? Mono.empty() : Mono.error(e));
    }

    @Override
    public Flux<Map<String, Object>> listKeys() {
        return Flux.from(mongo.collection(KEYS).find().sort(Sorts.ascending("_id"))).map(d -> {
            Map<String, Object> jwk = new LinkedHashMap<>(d);
            jwk.remove("_id");
            return jwk;
        });
    }
}
