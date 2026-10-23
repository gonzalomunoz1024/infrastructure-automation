package com.iaf.console.bff.session.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import com.iaf.console.bff.session.domain.AccountMembership;
import com.iaf.console.bff.session.domain.Principal;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.bff.session.domain.SessionKeys;
import com.iaf.console.kit.mongo.Mongo;

/** Review finding 7: a store keeps only the hash of a session id, and no write but the first inserts. */
class SessionStoresTest {

    private static final Instant T0 = Instant.parse("2026-10-23T08:00:00Z");
    private static final String ID = "s3cr3t-session-id-from-the-cookie";

    @Test
    void theInMemoryStoreKeysOnTheHashAndHandsTheIdBack() {
        InMemorySessionStore store = new InMemorySessionStore(Clock.fixed(T0, ZoneOffset.UTC));
        store.create(session(ID)).block();
        assertEquals(Set.of(SessionKeys.of(ID)), store.keys());
        assertEquals(64, SessionKeys.of(ID).length());
        Session found = store.find(ID).block();
        assertEquals(ID, found.id(), "the caller's id, never the stored hash");
        assertNull(store.find(SessionKeys.of(ID)).block(), "the hash is no key to the session");
    }

    @Test
    void aLoggedOutSessionStaysLoggedOut() {
        InMemorySessionStore store = new InMemorySessionStore(Clock.fixed(T0, ZoneOffset.UTC));
        Session session = session(ID);
        store.create(session).block();
        assertTrue(store.update(session.seenAt(T0.plusSeconds(90))).block());
        assertEquals(T0.plusSeconds(90), store.find(ID).block().lastSeenAt());
        store.delete(ID).block();
        assertFalse(store.update(session.seenAt(T0.plusSeconds(120))).block(), "a request in flight finds it gone");
        assertNull(store.find(ID).block(), "and does not bring it back");
        assertEquals(0, store.size());
    }

    @Test
    void theMongoDocumentHoldsNoSessionId() {
        MongoSessionStore store = new MongoSessionStore(new Mongo(null));
        Document d = store.document(session(ID));
        assertEquals(SessionKeys.of(ID), d.get("_id"));
        assertEquals(SessionKeys.of(ID), d.get(Mongo.DOC, Document.class).get("id"));
        assertFalse(d.toJson().contains(ID), d.toJson());
        assertEquals("user_dana", d.get("userId"));
    }

    private static Session session(String id) {
        return new Session(id, new Principal("user_dana", "dana@example.com", "Dana", List.of()),
                List.of(new AccountMembership("123456789012", "Compute", "active", "global", List.of("members"), List.of("operator"))), "123456789012",
                "global", T0, T0, T0, T0.plus(Duration.ofHours(12)), "https://idp.example", "dana", List.of("idp"), T0, T0.plusSeconds(300));
    }
}
