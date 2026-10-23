package com.iaf.console.bff.clients.adapters.outbound;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.console.bff.clients.domain.DeviceGrant;
import com.iaf.console.bff.clients.ports.outbound.DeviceGrantStore;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Updates;

import reactor.core.publisher.Mono;

/**
 * Device grants shared by every instance, in the {@code device_grants} collection: {@code _id} the
 * canonical user code, the grant under {@code doc}, and beside it what the queries read: the
 * device code's SHA-256 (unique), the address and time it was asked from (the rate limit's count),
 * {@code version}, and {@code purgeAt}, ten minutes past the grant's expiry, which a TTL index
 * removes it at and every read honours before that index's minute-long sweep.
 */
@Component
@Profile("mongo")
public class MongoDeviceGrantStore implements DeviceGrantStore, MongoIndexed {

    static final String COLLECTION = "device_grants";

    private final Mongo mongo;

    public MongoDeviceGrantStore(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return mongo.indexes(COLLECTION, List.of(
                new IndexModel(Indexes.ascending("deviceCodeHash"), new IndexOptions().unique(true)),
                new IndexModel(Indexes.ascending("ip", "requestedAt")),
                new IndexModel(Indexes.ascending("purgeAt"), new IndexOptions().expireAfter(0L, TimeUnit.SECONDS))));
    }

    @Override
    public Mono<Boolean> create(DeviceGrant grant) {
        return Mono.defer(() -> Mono.from(mongo.collection(COLLECTION).insertOne(document(grant))))
                .thenReturn(true)
                .onErrorResume(MongoException.class, e -> Mongo.duplicateKey(e) ? Mono.just(false) : Mono.error(e));
    }

    @Override
    public Mono<DeviceGrant> findByUserCode(String userCode, Instant now) {
        return find(Filters.and(Filters.eq("_id", userCode), kept(now)));
    }

    @Override
    public Mono<DeviceGrant> findByDeviceCode(String deviceCodeHash, Instant now) {
        return find(Filters.and(Filters.eq("deviceCodeHash", deviceCodeHash), kept(now)));
    }

    @Override
    public Mono<Long> countRequestedFrom(String ip, Instant since) {
        return Mono.from(mongo.collection(COLLECTION).countDocuments(Filters.and(Filters.eq("ip", ip), Filters.gte("requestedAt", Mongo.date(since)))));
    }

    @Override
    public Mono<DeviceGrant> update(DeviceGrant grant, long expectedVersion) {
        Bson current = Filters.and(Filters.eq("_id", grant.userCode()), Filters.eq("deviceCodeHash", grant.deviceCodeHash()),
                Filters.eq("version", expectedVersion));
        Bson next = Updates.combine(Updates.set("version", grant.version()), Updates.set(Mongo.DOC, mongo.doc(grant)));
        return Mono.from(mongo.collection(COLLECTION).updateOne(current, next)).flatMap(r -> r.getMatchedCount() > 0 ? Mono.just(grant) : Mono.empty());
    }

    /** A grant as it is stored. */
    Document document(DeviceGrant grant) {
        return new Document("_id", grant.userCode())
                .append("deviceCodeHash", grant.deviceCodeHash())
                .append("ip", grant.requestedFrom().ip())
                .append("requestedAt", Mongo.date(grant.requestedAt()))
                .append("purgeAt", Mongo.date(grant.purgeAt()))
                .append("version", grant.version())
                .append(Mongo.DOC, mongo.doc(grant));
    }

    DeviceGrant read(Document d) {
        return mongo.read(d, DeviceGrant.class);
    }

    private Mono<DeviceGrant> find(Bson filter) {
        return Mono.from(mongo.collection(COLLECTION).find(filter).first()).map(this::read);
    }

    private static Bson kept(Instant now) {
        return Filters.gt("purgeAt", Mongo.date(now));
    }
}
