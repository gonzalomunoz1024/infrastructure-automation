package com.iaf.console.bff.assistant.adapters.outbound;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.console.bff.assistant.domain.TurnAllowance;
import com.iaf.console.bff.assistant.ports.outbound.TurnLimiter;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.PushOptions;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;

import reactor.core.publisher.Mono;

/**
 * A person's turns across every instance, in the {@code assistant_turns} collection: one document
 * per person ({@code _id} the user id) with {@code at}, the times of their last turns, oldest
 * first and never more than the limit, and {@code expiresAt}, whose TTL index drops the document
 * of someone who stopped asking. Taking a turn is one atomic update: it pushes now when the list
 * has room or its oldest time left the window; otherwise the upsert meets the person's document
 * and fails on its key, which is the refusal, and the oldest time says when a turn is free. Two
 * first turns at once both upsert and one fails on the key while the document has room, so a
 * failed upsert reads the document and tries again while it has room.
 */
@Component
@Profile("mongo")
public class MongoTurnLimiter implements TurnLimiter, MongoIndexed {

    static final String COLLECTION = "assistant_turns";

    /** Upserts a turn may take: the first, and one more for each race lost to a turn taken at the same moment. */
    static final int ATTEMPTS = 3;

    private final Mongo mongo;

    public MongoTurnLimiter(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return mongo.indexes(COLLECTION, List.of(new IndexModel(Indexes.ascending("expiresAt"), new IndexOptions().expireAfter(0L, TimeUnit.SECONDS))));
    }

    @Override
    public Mono<TurnAllowance> take(String userId, Instant now, int limit, Duration window) {
        return attempt(userId, now, limit, window, ATTEMPTS);
    }

    private Mono<TurnAllowance> attempt(String userId, Instant now, int limit, Duration window, int attempts) {
        return Mono.defer(() -> Mono.from(mongo.collection(COLLECTION).updateOne(room(userId, now, limit, window), take(now, limit, window),
                        new UpdateOptions().upsert(true))))
                .map(r -> TurnAllowance.granted())
                .onErrorResume(MongoException.class, e -> Mongo.duplicateKey(e) ? lost(userId, now, limit, window, attempts) : Mono.error(e));
    }

    /** The upsert met the person's document: a refusal when it is full, else a race lost to another turn, tried again. */
    private Mono<TurnAllowance> lost(String userId, Instant now, int limit, Duration window, int attempts) {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.eq("_id", userId)).first())
                .filter(d -> !hasRoom(d, now, limit, window))
                .map(d -> TurnAllowance.refused(wait(d, now, window)))
                .switchIfEmpty(Mono.defer(() -> attempts > 1 ? attempt(userId, now, limit, window, attempts - 1)
                        : Mono.just(TurnAllowance.refused(Duration.ofSeconds(1)))));
    }

    /** The person's document when it has room for a turn now: fewer times than the limit, or the oldest outside the window. */
    static Bson room(String userId, Instant now, int limit, Duration window) {
        return Filters.and(Filters.eq("_id", userId),
                Filters.or(Filters.exists("at." + (limit - 1), false), Filters.lte("at.0", Mongo.date(now.minus(window)))));
    }

    /** Now among the times, which stay sorted and the newest {@code limit} of them. */
    static Bson take(Instant now, int limit, Duration window) {
        return Updates.combine(
                Updates.pushEach("at", List.of(Mongo.date(now)), new PushOptions().sort(1).slice(-limit)),
                Updates.set("expiresAt", Mongo.date(now.plus(window))));
    }

    /** Whether the document has room for a turn now, as {@link #room} asks it. */
    static boolean hasRoom(Document d, Instant now, int limit, Duration window) {
        List<Date> at = d.getList("at", Date.class);
        return at == null || at.size() < limit || !at.get(0).toInstant().isAfter(now.minus(window));
    }

    /** How long until the oldest time of the document leaves the window. */
    static Duration wait(Document d, Instant now, Duration window) {
        List<Date> at = d.getList("at", Date.class);
        if (at == null || at.isEmpty()) {
            return Duration.ofSeconds(1);
        }
        Instant oldest = at.get(0).toInstant();
        Duration left = Duration.between(now, oldest.plus(window));
        return left.isNegative() ? Duration.ofSeconds(1) : left;
    }
}
