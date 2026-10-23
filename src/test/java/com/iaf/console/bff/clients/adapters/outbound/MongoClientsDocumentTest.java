package com.iaf.console.bff.clients.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import com.iaf.console.bff.clients.domain.ApiSession;
import com.iaf.console.bff.clients.domain.DeviceGrant;
import com.iaf.console.bff.clients.domain.RequestedFrom;
import com.iaf.console.bff.clients.domain.Secrets;
import com.iaf.console.bff.session.domain.AccountMembership;
import com.iaf.console.bff.session.domain.Principal;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.kit.mongo.Mongo;

/**
 * Device grants and API sessions as the Mongo stores write and read them, without a database:
 * what the queries and the TTL indexes read beside the record, the record back whole, and no
 * device code or refresh token anywhere, only their hashes.
 */
class MongoClientsDocumentTest {

    private static final Instant AT = Instant.parse("2026-10-23T12:00:00.123456789Z");
    private static final String COMPUTE = "123456789012";

    private final Mongo mongo = new Mongo(null);
    private final String deviceCode = Secrets.generate();
    private final String refreshToken = Secrets.generate();
    private final Session browser = new Session("cookie-session-id", new Principal("user_gonzalo", "gonzalo@example.com", "Gonzalo Munoz", List.of("owners")),
            List.of(new AccountMembership(COMPUTE, "Compute", "active", "global", List.of("owners"), List.of("account-admin"))), COMPUTE, "global",
            AT.minus(Duration.ofHours(1)), AT.minusSeconds(30), AT, AT.plus(Duration.ofHours(11)), "http://idp", "gonzalo");
    private final DeviceGrant grant = DeviceGrant.requested("BCDFGHJK", Secrets.hash(deviceCode), "console-cli", new RequestedFrom("10.0.0.7", "cli/1"), AT,
            Duration.ofMinutes(10), Duration.ofSeconds(5));

    @Test
    void aGrantIsStoredWithWhatItsQueriesReadAndReadBackWhole() {
        MongoDeviceGrantStore store = new MongoDeviceGrantStore(mongo);
        DeviceGrant approved = grant.approve(DeviceGrant.Approver.of(browser), AT.plusSeconds(20));
        Document stored = store.document(approved);
        assertEquals("BCDFGHJK", stored.get("_id"));
        assertEquals(Secrets.hash(deviceCode), stored.get("deviceCodeHash"));
        assertEquals("10.0.0.7", stored.get("ip"));
        assertEquals(Date.from(AT), stored.get("requestedAt"));
        assertEquals(Mongo.date(AT.plus(Duration.ofMinutes(20))), stored.get("purgeAt"), "ten minutes past the expiry");
        assertEquals(2L, stored.get("version"));
        assertEquals(approved, store.read(stored));
        assertFalse(stored.toJson().contains(deviceCode), "never the device code itself");
        assertFalse(stored.toJson().contains(browser.id()), "never the approver's cookie");
    }

    @Test
    void aSessionIsStoredWithWhatItsQueriesReadAndReadBackWhole() {
        MongoApiSessionStore store = new MongoApiSessionStore(mongo);
        DeviceGrant redeemed = grant.approve(DeviceGrant.Approver.of(browser), AT.plusSeconds(20)).poll(AT.plusSeconds(25), ApiSession.newId()).next();
        ApiSession api = ApiSession.open(redeemed, Secrets.hash(refreshToken), new RequestedFrom("10.0.0.7", "cli/1"), AT.plusSeconds(25),
                Duration.ofHours(12), Duration.ofHours(8));
        ApiSession rotated = api.rotated(Secrets.hash("next"), api.user(), api.accounts(), AT.plus(Duration.ofHours(1)), Duration.ofHours(8));
        Document stored = store.document(rotated);
        assertEquals(api.id(), stored.get("_id"));
        assertEquals("user_gonzalo", stored.get("userId"));
        assertEquals(Mongo.nanos(api.createdAt()), stored.get("createdAtNs"));
        assertEquals(Secrets.hash("next"), stored.get("refreshHash"));
        assertEquals(List.of(Secrets.hash(refreshToken)), stored.get("rotatedHashes"));
        assertEquals(false, stored.get("revoked"));
        assertEquals(Mongo.date(rotated.purgeAt()), stored.get("endsAt"));
        assertEquals(2L, stored.get("version"));
        assertEquals(rotated, store.read(stored));
        assertFalse(stored.toJson().contains(refreshToken), "never the refresh token itself");

        Document revoked = store.document(rotated.revoked(AT.plus(Duration.ofHours(2)), "signed out"));
        assertEquals(true, revoked.get("revoked"));
        assertTrue(store.read(revoked).revokedAt() != null);
    }
}
