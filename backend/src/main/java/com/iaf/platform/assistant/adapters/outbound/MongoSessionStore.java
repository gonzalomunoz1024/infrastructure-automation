package com.iaf.platform.assistant.adapters.outbound;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.UnaryOperator;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.platform.assistant.domain.AssistantSession;
import com.iaf.platform.assistant.ports.outbound.SessionStore;
import com.iaf.platform.common.adapters.mongo.Mongo;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;

/**
 * The builder sessions of the {@code mongo} profile: one document per session in
 * {@code assistant_sessions}, its doc the {@link AssistantSession}, so a conversation and its
 * draft survive a restart. An update holds the session while it changes it, as the row lock did,
 * so concurrent turns never lose one another: it claims a lease with a conditional
 * {@code findOneAndUpdate} that only matches a session nobody holds ({@code lease}, a token of
 * its own, until {@code leaseUntil}), changes what that returned, and writes the result only while
 * it still holds the lease, in the same write that gives the lease up. Another update waits
 * meanwhile, however many updates take their turn before it, and gives up only when one holder
 * keeps the session past three leases. A holder that crashed keeps it for {@link #LEASE} at most; one that
 * outlived its lease finds its write refused and starts again from what is stored then, so its
 * change runs twice. Lease times are the database's clock ({@code $$NOW}), so replicas whose
 * clocks differ agree on them. A save replaces the session whole, lease included, so an update
 * holding it at that moment starts again on top of the saved session. A change that returns null
 * removes the session, as in memory. {@code SessionRetention} deletes the sessions not updated
 * within {@code platform.assistant.session-retention}, except one an update holds at the time.
 */
@Component
@Primary
@Profile("mongo")
public class MongoSessionStore implements SessionStore {

    static final String COLLECTION = "assistant_sessions";
    /** How long an update may hold a session before another update may take it over. */
    static final Duration LEASE = Duration.ofSeconds(10);

    /** A session nobody holds: never claimed, given back, or held past its lease (its holder crashed or stalled). */
    private static final Bson FREE = Filters.or(Filters.eq("leaseUntil", null), Filters.expr(new Document("$lte", List.of("$leaseUntil", "$$NOW"))));
    private static final long LONGEST_PAUSE_MS = 50;

    private final Mongo mongo;
    private final Duration lease;

    @Autowired
    public MongoSessionStore(Mongo mongo) {
        this(mongo, LEASE);
    }

    /** @param lease how long an update may hold a session; shorter in tests */
    MongoSessionStore(Mongo mongo, Duration lease) {
        this.mongo = mongo;
        this.lease = lease;
        mongo.index(COLLECTION, Indexes.ascending("updatedNs"));
    }

    @Override
    public void save(AssistantSession session) {
        mongo.collection(COLLECTION).replaceOne(Filters.eq("_id", session.id()), document(session), new ReplaceOptions().upsert(true));
    }

    @Override
    public Optional<AssistantSession> get(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(mongo.collection(COLLECTION).find(Filters.eq("_id", id)).projection(Projections.include(Mongo.DOC)).first())
                .map(d -> mongo.read(d, AssistantSession.class));
    }

    @Override
    public Optional<AssistantSession> update(String id, UnaryOperator<AssistantSession> change) {
        if (id == null) {
            return Optional.empty();
        }
        String holder = UUID.randomUUID().toString();
        Bson mine = Filters.and(Filters.eq("_id", id), Filters.eq("lease", holder));
        String waitingOn = null;
        long waitingSince = 0;
        long pause = 1;
        while (true) {
            Document held = mongo.collection(COLLECTION).findOneAndUpdate(Filters.and(Filters.eq("_id", id), FREE), claim(holder),
                    new FindOneAndUpdateOptions().projection(Projections.include(Mongo.DOC)).returnDocument(ReturnDocument.AFTER));
            if (held != null) {
                AssistantSession next;
                try {
                    next = change.apply(mongo.read(held, AssistantSession.class));
                } catch (RuntimeException | Error e) {
                    mongo.collection(COLLECTION).updateOne(mine, Updates.combine(Updates.unset("lease"), Updates.unset("leaseUntil")));
                    throw e;
                }
                boolean written = next == null
                        ? mongo.collection(COLLECTION).deleteOne(mine).getDeletedCount() == 1
                        : mongo.collection(COLLECTION).replaceOne(mine, document(next)).getMatchedCount() == 1;
                if (written) {
                    return Optional.ofNullable(next);
                }
                continue;   // the lease ran out before the write and the session changed hands: start again from what is stored now
            }
            Document current = mongo.collection(COLLECTION).find(Filters.eq("_id", id)).projection(Projections.include("lease")).first();
            if (current == null) {
                return Optional.empty();
            }
            String other = Objects.toString(current.get("lease"));
            if (!other.equals(waitingOn)) {
                // another update holds it now, for moments as a rule: look again soon
                waitingOn = other;
                waitingSince = System.nanoTime();
                pause = 1;
            } else if (System.nanoTime() - waitingSince > lease.multipliedBy(3).toNanos()) {
                throw new IllegalStateException("assistant session " + id + " stayed held by one update for longer than three leases");
            }
            pause(pause);
            pause = Math.min(pause * 2, LONGEST_PAUSE_MS);   // the same holder all along: it may have crashed, and its lease has to run out
        }
    }

    @Override
    public int deleteUpdatedBefore(Instant cutoff) {
        return (int) mongo.collection(COLLECTION).deleteMany(Filters.and(Filters.lt("updatedNs", Mongo.nanos(cutoff)), FREE)).getDeletedCount();
    }

    /** The session as stored, without a lease. */
    private Document document(AssistantSession s) {
        return new Document("_id", s.id()).append("updatedNs", Mongo.nanos(s.updatedAt())).append(Mongo.DOC, mongo.doc(s));
    }

    /** The lease, as an update pipeline, so it runs out on the database's clock. */
    private List<Bson> claim(String holder) {
        return List.of(new Document("$set", new Document("lease", holder).append("leaseUntil", new Document("$add", List.of("$$NOW", lease.toMillis())))));
    }

    /** A pause of up to {@code millis}, at random from half of it, so waiting updates do not knock in step. */
    private static void pause(long millis) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(millis / 2, millis + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for an assistant session", e);
        }
    }
}
