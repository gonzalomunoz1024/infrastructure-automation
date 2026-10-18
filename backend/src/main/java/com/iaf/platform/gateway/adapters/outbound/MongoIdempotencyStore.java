package com.iaf.platform.gateway.adapters.outbound;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.platform.common.adapters.mongo.Mongo;
import com.iaf.platform.gateway.domain.KeyedRequest;
import com.iaf.platform.gateway.domain.StoredAnswer;
import com.iaf.platform.gateway.ports.outbound.IdempotencyStore;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;

/**
 * The idempotency keys of the {@code mongo} profile: one document per caller and key in
 * {@code idempotency_keys}, keyed {@code <caller>\n<key>} (a key is visible ASCII, so the last
 * line break is the one between them), shared by every API replica. Its doc is the
 * {@link KeyedRequest}: the fingerprint of the request the key was first used with, when it was
 * claimed, until when it answers, and the answer once that request succeeded; {@code status}
 * beside it is the answer's status, absent while the request is in flight.
 *
 * <p>A claim is one conditional upsert: it inserts the key, or replaces a stored one that no
 * longer holds (expired, or in flight past {@link KeyedRequest#IN_FLIGHT_TIMEOUT}), in one atomic
 * write; when the stored key still holds, the upsert collides with it and the key is returned
 * instead. A completion sets the answer, a release deletes an unanswered key. A TTL index on
 * {@code expiresAt} deletes a key about a minute after its expiry
 * ({@code platform.gateway.idempotency.ttl} from when it was claimed); until then an expired key
 * reads as absent, since a claim takes it over.
 */
@Component
@Primary
@Profile("mongo")
public class MongoIdempotencyStore implements IdempotencyStore {

    static final String COLLECTION = "idempotency_keys";
    private static final int ATTEMPTS = 3;

    private final Mongo mongo;

    public MongoIdempotencyStore(Mongo mongo) {
        this.mongo = mongo;
        mongo.index(COLLECTION, Indexes.ascending("expiresAt"), new IndexOptions().expireAfter(0L, TimeUnit.SECONDS));
    }

    @Override
    public Optional<KeyedRequest> claim(KeyedRequest inFlight, Instant now) {
        String id = id(inFlight.caller(), inFlight.key());
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            try {
                mongo.collection(COLLECTION).updateOne(Filters.and(Filters.eq("_id", id), heldNoLongerAt(now)), stored(id, inFlight),
                        new UpdateOptions().upsert(true));
                return Optional.empty();
            } catch (MongoException e) {
                if (!Mongo.duplicateKey(e)) {
                    throw e;
                }
            }
            Optional<KeyedRequest> held = Optional.ofNullable(mongo.collection(COLLECTION).find(Filters.eq("_id", id)).first())
                    .map(d -> mongo.read(d, KeyedRequest.class))
                    .filter(k -> k.liveAt(now));
            if (held.isPresent()) {
                return held;
            }
            // released, or changed, between the upsert and the read: claim again
        }
        throw new IllegalStateException("could not claim idempotency key '" + inFlight.key() + "' for " + inFlight.caller());
    }

    /** Sets the answer in the stored request, as an update of the request's own doc, and its status beside it. */
    @Override
    public void complete(String caller, String key, StoredAnswer answer) {
        mongo.collection(COLLECTION).updateOne(Filters.eq("_id", id(caller, key)),
                Updates.combine(Updates.set("status", answer.status()), Updates.set(Mongo.DOC + ".answer", mongo.doc(answer))));
    }

    @Override
    public void release(String caller, String key) {
        mongo.collection(COLLECTION).deleteOne(Filters.and(Filters.eq("_id", id(caller, key)), Filters.eq("status", null)));
    }

    /** For tests: how many keys are kept, expired ones included until the TTL monitor deletes them. */
    int size() {
        return (int) mongo.collection(COLLECTION).countDocuments();
    }

    static String id(String caller, String key) {
        return caller + "\n" + key;
    }

    /** A stored key that no longer holds at {@code now}, as {@link KeyedRequest#liveAt} has it: expired, or in flight and abandoned. */
    private static Bson heldNoLongerAt(Instant now) {
        return Filters.or(Filters.lte("expiresNs", Mongo.nanos(now)),
                Filters.and(Filters.eq("status", null), Filters.lte("createdNs", Mongo.nanos(now.minus(KeyedRequest.IN_FLIGHT_TIMEOUT)))));
    }

    /**
     * The key as stored, written as an update pipeline: the request whole (as a literal, so no
     * value is read as an expression), the instants a claim compares, and {@code expiresAt}, the
     * database's own time plus the key's lifetime, so the database's clock alone decides when the
     * TTL monitor deletes it.
     */
    private List<Bson> stored(String id, KeyedRequest r) {
        Document key = new Document("_id", id)
                .append("createdNs", Mongo.nanos(r.createdAt()))
                .append("expiresNs", Mongo.nanos(r.expiresAt()));
        if (r.answered()) {
            key.append("status", r.answer().status());
        }
        key.append(Mongo.DOC, mongo.doc(r));
        long lifetime = Duration.between(r.createdAt(), r.expiresAt()).toMillis();
        return List.of(new Document("$replaceWith", new Document("$literal", key)),
                new Document("$set", new Document("expiresAt", new Document("$add", List.of("$$NOW", lifetime)))));
    }
}
