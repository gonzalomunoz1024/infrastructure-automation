package com.iaf.platform.assistant.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.iaf.platform.assistant.domain.AssistantSession;
import com.iaf.platform.assistant.ports.outbound.SessionStore;
import com.iaf.platform.common.adapters.mongo.Mongo;
import com.iaf.platform.common.adapters.mongo.TestMongo;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;

@EnabledIfEnvironmentVariable(named = TestMongo.MONGO_URI, matches = ".+")
class MongoSessionStoreTest extends SessionStoreContract {

    @RegisterExtension
    final TestMongo db = new TestMongo();

    @Override
    SessionStore newStore() {
        return new MongoSessionStore(db.mongo());
    }

    @Override
    SessionStore reopen() {
        return new MongoSessionStore(new Mongo(db.database()));
    }

    MongoCollection<Document> sessions() {
        return db.database().getCollection(MongoSessionStore.COLLECTION);
    }

    /** Another replica's lease on the session, as the database would keep it, running out {@code millis} from now. */
    void heldElsewhere(String id, long millis) {
        sessions().updateOne(Filters.eq("_id", id), List.of(new Document("$set", new Document("lease", "another-replica")
                .append("leaseUntil", new Document("$add", List.of("$$NOW", millis))))));
    }

    static List<String> texts(AssistantSession s) {
        return s.history().stream().map(AssistantSession.Turn::text).toList();
    }

    @Test
    void anUpdateWaitsForTheHolderAndTakesOverALeaseThatRanOut() {
        SessionStore store = newStore();
        store.save(session("as_1"));
        heldElsewhere("as_1", 400);   // a holder that crashed: nobody gives this lease back
        AtomicInteger calls = new AtomicInteger();
        long start = System.nanoTime();

        Optional<AssistantSession> updated = store.update("as_1", s -> {
            calls.incrementAndGet();
            return s.withStatus(AssistantSession.DONE, NOW);
        });

        assertThat(Duration.ofNanos(System.nanoTime() - start)).as("waited for the lease to run out").isGreaterThan(Duration.ofMillis(250));
        assertThat(updated).map(AssistantSession::status).contains(AssistantSession.DONE);
        assertThat(calls).as("the change ran once, on the session as stored").hasValue(1);
        assertThat(sessions().find().first()).as("the write gave the lease up").doesNotContainKeys("lease", "leaseUntil");
    }

    @Test
    void anUpdateThatOutlivedItsLeaseStartsAgainOnWhatTheNextHolderWrote() {
        MongoSessionStore slow = new MongoSessionStore(db.mongo(), Duration.ofMillis(200));
        SessionStore other = new MongoSessionStore(db.mongo(), Duration.ofMillis(200));
        slow.save(session("as_1"));
        AtomicInteger calls = new AtomicInteger();

        Optional<AssistantSession> updated = slow.update("as_1", s -> {
            if (calls.incrementAndGet() == 1) {
                // while this change runs, its lease runs out and another replica's update holds the session and writes
                other.update("as_1", x -> x.withTurn("assistant", "meanwhile", NOW));
            }
            return s.withTurn("user", "slow", NOW);
        });

        assertThat(calls).hasValue(2);
        assertThat(texts(updated.orElseThrow())).endsWith("meanwhile", "slow");
        assertThat(texts(slow.get("as_1").orElseThrow())).endsWith("meanwhile", "slow");
    }

    @Test
    void aSaveWhileAnUpdateHoldsTheSessionIsNotLost() {
        SessionStore store = newStore();
        store.save(session("as_1"));
        AtomicInteger calls = new AtomicInteger();

        store.update("as_1", s -> {
            if (calls.incrementAndGet() == 1) {
                store.save(s.withTurn("user", "saved meanwhile", NOW));
            }
            return s.withTurn("assistant", "the update", NOW);
        });

        assertThat(calls).hasValue(2);
        assertThat(texts(store.get("as_1").orElseThrow())).endsWith("saved meanwhile", "the update");
    }

    @Test
    void aChangeThatFailsGivesTheLeaseBackAtOnce() {
        SessionStore store = newStore();
        store.save(session("as_1"));

        assertThatThrownBy(() -> store.update("as_1", s -> {
            throw new IllegalArgumentException("a bad change");
        })).isInstanceOf(IllegalArgumentException.class);

        assertThat(sessions().find().first()).doesNotContainKeys("lease", "leaseUntil");
        long start = System.nanoTime();
        assertThat(store.update("as_1", s -> s.withStatus(AssistantSession.DONE, NOW))).isPresent();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(MongoSessionStore.LEASE);
    }

    @Test
    void retentionLeavesASessionAnUpdateHoldsAndDeletesOneWhoseHolderIsGone() {
        SessionStore store = newStore();
        Instant cutoff = NOW.minus(Duration.ofDays(30));
        store.save(session("as_held").withStatus(AssistantSession.DONE, cutoff.minusSeconds(1)));
        store.save(session("as_abandoned").withStatus(AssistantSession.DONE, cutoff.minusSeconds(1)));
        heldElsewhere("as_held", 60_000);
        heldElsewhere("as_abandoned", -1_000);   // its holder's lease ran out a second ago

        assertThat(store.deleteUpdatedBefore(cutoff)).isEqualTo(1);

        assertThat(store.get("as_held")).isPresent();
        assertThat(store.get("as_abandoned")).isEmpty();
    }
}
