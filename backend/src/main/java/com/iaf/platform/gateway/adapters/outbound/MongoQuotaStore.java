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
import com.iaf.platform.gateway.domain.TokenBucket;
import com.iaf.platform.gateway.ports.outbound.QuotaStore;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;

/**
 * The quotas of the {@code mongo} profile: one document per bucket in {@code quota_buckets},
 * keyed by the bucket and shared by every API replica, with its {@code tokens} and when they were
 * last refilled ({@code lastNs}), as {@link TokenBucket} keeps them. A take is two atomic writes,
 * with no read before either: the first refills the bucket to now on the server (an update
 * pipeline that restores it under the current limit, clamped to its burst, and adds what its rate
 * has brought since {@code lastNs}; a bucket that does not exist, or has been idle for
 * {@value #IDLE_HOURS} hours, starts full), and the second takes a token with a conditional
 * {@code findOneAndUpdate} that subtracts one with {@code $inc} only while the bucket holds one,
 * so two replicas never hand out the same token. A bucket idle for {@value #IDLE_HOURS} hours is
 * full again, so a TTL index on {@code touchedAt}, the database's time of the bucket's last take,
 * deletes it.
 */
@Component
@Primary
@Profile("mongo")
public class MongoQuotaStore implements QuotaStore {

    static final String COLLECTION = "quota_buckets";
    static final long IDLE_HOURS = 24;

    private static final long IDLE_NANOS = Duration.ofHours(IDLE_HOURS).toNanos();
    private static final double NANOS_PER_MINUTE = 60_000_000_000d;
    /** Takes lost in a row before giving up; each one lost is a token another replica took meanwhile. */
    private static final int ATTEMPTS = 100;

    private final Mongo mongo;

    public MongoQuotaStore(Mongo mongo) {
        this.mongo = mongo;
        mongo.index(COLLECTION, Indexes.ascending("touchedAt"), new IndexOptions().expireAfter(Duration.ofHours(IDLE_HOURS).toSeconds(), TimeUnit.SECONDS));
    }

    @Override
    public Optional<Duration> take(String bucket, int perMinute, int burst, Instant now) {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            Document refilled = mongo.collection(COLLECTION).findOneAndUpdate(Filters.eq("_id", bucket), refill(perMinute, burst, now),
                    new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
            double tokens = refilled.get("tokens", Number.class).doubleValue();
            if (tokens < 1d) {
                Instant last = instant(refilled.get("lastNs", Number.class).longValue());
                return TokenBucket.restore(perMinute, burst, tokens, last).take(now);
            }
            Document taken = mongo.collection(COLLECTION).findOneAndUpdate(Filters.and(Filters.eq("_id", bucket), Filters.gte("tokens", 1d)),
                    Updates.inc("tokens", -1d), new FindOneAndUpdateOptions().projection(Projections.include("_id")));
            if (taken != null) {
                return Optional.empty();
            }
            // another replica took the token between the two writes: refill and look again
        }
        throw new IllegalStateException("quota bucket " + bucket + " lost " + ATTEMPTS + " takes in a row to other replicas");
    }

    /** One token back, never above {@code burst}, in one write; nothing when the bucket does not exist. */
    @Override
    public void giveBack(String bucket, int burst) {
        mongo.collection(COLLECTION).updateOne(Filters.eq("_id", bucket),
                List.of(new Document("$set", new Document("tokens", op("$min", (double) burst, op("$add", "$tokens", 1d))))));
    }

    /** The bucket's tokens as kept, without refilling (for tests and diagnosis). */
    public Optional<Double> tokensOf(String bucket) {
        return Optional.ofNullable(mongo.collection(COLLECTION).find(Filters.eq("_id", bucket)).projection(Projections.include("tokens")).first())
                .map(d -> d.get("tokens", Number.class).doubleValue());
    }

    /**
     * The update that refills a bucket to {@code now} as {@link TokenBucket#restore} and a take
     * do: the tokens kept, clamped to {@code [0, burst]} of the current limit, plus what
     * {@code perMinute} has added since {@code lastNs}, never above {@code burst}; {@code lastNs}
     * moves to {@code now} unless a replica has refilled the bucket to a later time already. A
     * bucket that does not exist, or has been idle for {@value #IDLE_HOURS} hours (which the TTL
     * monitor may not have deleted yet), starts full at {@code now}. The arithmetic is the
     * domain's, step for step, in doubles.
     */
    private static List<Bson> refill(int perMinute, int burst, Instant now) {
        long at = Mongo.nanos(now);
        Document fresh = op("$lt", op("$ifNull", "$lastNs", Long.MIN_VALUE), at - IDLE_NANOS);
        Document start = new Document("$set", new Document("tokens", op("$cond", fresh, (double) burst, "$tokens"))
                .append("lastNs", op("$cond", fresh, at, "$lastNs")));
        Document kept = op("$max", 0d, op("$min", (double) burst, "$tokens"));
        Document elapsed = new Document("$toDouble", op("$subtract", at, "$lastNs"));
        Document added = op("$divide", op("$multiply", elapsed, (double) perMinute), NANOS_PER_MINUTE);
        Document later = op("$gt", at, "$lastNs");
        Document refilled = new Document("$set", new Document("tokens", op("$cond", later, op("$min", (double) burst, op("$add", kept, added)), kept))
                .append("lastNs", op("$cond", later, at, "$lastNs"))
                .append("touchedAt", "$$NOW"));
        return List.of(start, refilled);
    }

    /** An aggregation operator over its arguments. */
    private static Document op(String operator, Object... arguments) {
        return new Document(operator, List.of(arguments));
    }

    private static Instant instant(long nanos) {
        return Instant.ofEpochSecond(Math.floorDiv(nanos, 1_000_000_000L), Math.floorMod(nanos, 1_000_000_000L));
    }
}
