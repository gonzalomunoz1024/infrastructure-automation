package com.iaf.console.bff.assistant.adapters.outbound;

import static com.iaf.console.bff.assistant.AssistantFixtures.COMPUTE;
import static com.iaf.console.bff.assistant.AssistantFixtures.DATA;
import static com.iaf.console.bff.assistant.AssistantFixtures.NOW;
import static com.iaf.console.bff.assistant.AssistantFixtures.WEB_01;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import com.iaf.console.bff.assistant.AssistantFixtures.MovingClock;
import com.iaf.console.bff.assistant.domain.AssistantProposal;
import com.iaf.console.bff.assistant.domain.AssistantTurn;
import com.iaf.console.bff.assistant.domain.Conversation;
import com.iaf.console.bff.assistant.domain.ResourceExplanation;
import com.iaf.console.bff.assistant.domain.SearchFilter;
import com.iaf.console.bff.assistant.domain.TurnAllowance;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.scope.AccountScope;

/** The conversation stores and the turn limiters: a conversation is found only by its person, and only for a day; ten turns a minute. */
class ConversationStoresTest {

    private static final AccountScope GONZALO = AccountScope.of(COMPUTE, "global", "user_gonzalo");
    private static final AccountScope DANA = AccountScope.of(COMPUTE, "global", "user_dana");

    @Test
    void aConversationInMemoryIsItsPersonsUntilItExpires() {
        MovingClock clock = new MovingClock(NOW);
        InMemoryConversationStore store = new InMemoryConversationStore(clock);
        Conversation c = asked(Conversation.start("user_gonzalo", COMPUTE, "global", NOW, Duration.ofHours(24)));
        store.insert(GONZALO, c).block();
        assertEquals(c, store.find(GONZALO, c.conversationId()).block());
        assertNull(store.find(DANA, c.conversationId()).block(), "another person's");
        assertNull(store.find(AccountScope.of(DATA, "global", "user_gonzalo"), c.conversationId()).block(), "another account's");
        assertThrows(IllegalStateException.class, () -> store.insert(DANA, c).block(), "a conversation is written in its own scope only");
        assertThrows(IllegalStateException.class, () -> store.insert(GONZALO, c).block(), "an id is used once");

        Conversation next = c.settled(AssistantTurn.answered(c.turns().get(1).turnId(), NOW, "done", null, null));
        assertEquals(next, store.replace(GONZALO, next, c.version()).block());
        assertNull(store.replace(GONZALO, next.settled(next.turns().get(1)), c.version()).block(), "a write on an old version is refused");

        clock.advance(Duration.ofHours(24));
        assertNull(store.find(GONZALO, c.conversationId()).block(), "a day after its start");
        assertEquals(0, store.size(), "and forgotten");
    }

    @Test
    void expiredConversationsAreSweptOnWrites() {
        MovingClock clock = new MovingClock(NOW);
        InMemoryConversationStore store = new InMemoryConversationStore(clock);
        for (int i = 0; i < 3; i++) {
            store.insert(GONZALO, asked(Conversation.start("user_gonzalo", COMPUTE, "global", clock.instant(), Duration.ofHours(24)))).block();
        }
        clock.advance(Duration.ofHours(25));
        store.insert(GONZALO, asked(Conversation.start("user_gonzalo", COMPUTE, "global", clock.instant(), Duration.ofHours(24)))).block();
        assertEquals(1, store.size());
    }

    @Test
    void aPersonHasTenTurnsInAnyMinute() {
        InMemoryTurnLimiter limiter = new InMemoryTurnLimiter();
        Duration minute = Duration.ofMinutes(1);
        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.take("user_mateo", NOW.plusSeconds(i), 10, minute).block().taken());
        }
        TurnAllowance refused = limiter.take("user_mateo", NOW.plusSeconds(20), 10, minute).block();
        assertEquals(new TurnAllowance(false, Duration.ofSeconds(40)), refused);
        assertEquals(40, refused.retryAfterSeconds());
        assertTrue(limiter.take("user_dana", NOW.plusSeconds(20), 10, minute).block().taken(), "each person has their own");
        assertTrue(limiter.take("user_mateo", NOW.plusSeconds(60), 10, minute).block().taken(), "the first turn left the window");
        assertEquals(1, limiter.take("user_mateo", NOW.plusSeconds(60).plusMillis(1), 10, minute).block().retryAfterSeconds(),
                "the wait is rounded up, and never under a second");
    }

    @Test
    void aConversationIsStoredInMongoWithWhatItsQueriesRead() {
        MongoConversationStore store = new MongoConversationStore(new Mongo(null), new MovingClock(NOW));
        Conversation c = asked(Conversation.start("user_gonzalo", COMPUTE, "global", NOW, Duration.ofHours(24)));
        Conversation answered = c.settled(AssistantTurn.answered(c.turns().get(1).turnId(), NOW.plusSeconds(3), "Here.", List.of(
                AssistantProposal.filter("Failed VMs", "compute", "vm", new SearchFilter(null, "failed", true, null, Map.of("env", "prod")), "-created"),
                AssistantProposal.create("Create web-9", "compute", "vm", Map.of("name", "web-9", "cpu", 4), List.of("image")),
                AssistantProposal.action("Restart web-01", "compute", "vm", WEB_01, "restart", Map.of(), List.of())),
                new ResourceExplanation(WEB_01, "It is fine.", List.of(new ResourceExplanation.Section("Drift", "None.")),
                        new ResourceExplanation.Facts(73.2, "USD", NOW, "Gonzalo Munoz", null, null, 2L, false))));
        Document stored = store.document(GONZALO, answered);
        assertEquals(c.conversationId(), stored.get("_id"));
        assertEquals("user_gonzalo", stored.get("userId"));
        assertEquals(COMPUTE, stored.get("accountId"));
        assertEquals("global", stored.get("region"));
        assertEquals(2L, stored.get("version"));
        assertEquals(Date.from(NOW.plus(Duration.ofHours(24))), stored.get("expiresAt"), "a date, for the TTL index");
        assertEquals(answered, store.read(stored), "what is stored reads back the same");
        assertThrows(IllegalStateException.class, () -> store.document(DANA, answered));
    }

    @Test
    void theMongoLimiterTakesATurnInOneUpdate() {
        Instant now = NOW;
        Duration minute = Duration.ofMinutes(1);
        assertEquals(Document.parse("""
                {"$and": [{"_id": "user_mateo"}, {"$or": [{"at.9": {"$exists": false}}, {"at.0": {"$lte": {"$date": "2026-10-23T09:59:00Z"}}}]}]}""").toJson(),
                Document.parse(MongoTurnLimiter.room("user_mateo", now, 10, minute).toBsonDocument().toJson()).toJson());
        String take = MongoTurnLimiter.take(now, 10, minute).toBsonDocument().toJson();
        assertTrue(take.contains("\"$slice\": -10") && take.contains("\"$sort\": 1"), take);
        Document full = new Document("at", List.of(Date.from(now.minusSeconds(45)), Date.from(now.minusSeconds(10))));
        assertEquals(Duration.ofSeconds(15), MongoTurnLimiter.wait(full, now, minute));
        assertEquals(Duration.ofSeconds(1), MongoTurnLimiter.wait(new Document(), now, minute));

        assertFalse(MongoTurnLimiter.hasRoom(full, now, 2, minute), "two turns in the minute, of two");
        assertTrue(MongoTurnLimiter.hasRoom(full, now, 3, minute), "a first turn lost its upsert to another one taken at the same moment");
        assertTrue(MongoTurnLimiter.hasRoom(full, now.plusSeconds(15), 2, minute), "the oldest left the window");
        assertTrue(MongoTurnLimiter.hasRoom(new Document(), now, 2, minute));
    }

    private static Conversation asked(Conversation c) {
        return c.asked(AssistantTurn.person(Conversation.newTurnId(), c.startedAt(), "show the failed vms"),
                AssistantTurn.thinking(Conversation.newTurnId(), c.startedAt()));
    }
}
