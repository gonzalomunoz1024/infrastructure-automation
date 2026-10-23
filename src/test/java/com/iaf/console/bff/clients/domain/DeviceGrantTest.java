package com.iaf.console.bff.clients.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.iaf.console.bff.session.domain.AccountMembership;
import com.iaf.console.bff.session.domain.Principal;
import com.iaf.console.bff.session.domain.Session;

/** The device grant's polling rules (RFC 8628 §3.5) and its one decision. */
class DeviceGrantTest {

    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");
    private static final String COMPUTE = "123456789012";

    private final DeviceGrant grant = DeviceGrant.requested("BCDFGHJK", Secrets.hash("device-code"), "console-cli",
            new RequestedFrom("10.0.0.7", "console-cli/1.0"), NOW, Duration.ofMinutes(10), Duration.ofSeconds(5));
    private final Session gonzalo = new Session("cookie-session-id", new Principal("user_gonzalo", "gonzalo@example.com", "Gonzalo Munoz", List.of("owners")),
            List.of(new AccountMembership(COMPUTE, "Compute", "active", "global", List.of("owners"), List.of("account-admin"))), COMPUTE, "global",
            NOW.minus(Duration.ofHours(1)), NOW.minusSeconds(30), NOW, NOW.plus(Duration.ofHours(11)), "http://idp", "gonzalo");

    @Test
    void aNewGrantIsPendingForItsTimeAndKeptTenMinutesMore() {
        assertEquals("pending", grant.statusAt(NOW));
        assertEquals(5, grant.interval());
        assertEquals(1, grant.version());
        assertEquals("expired", grant.statusAt(NOW.plus(Duration.ofMinutes(10))));
        assertTrue(grant.kept(NOW.plus(Duration.ofMinutes(19))));
        assertFalse(grant.kept(NOW.plus(Duration.ofMinutes(20))));
        assertEquals(NOW.plus(Duration.ofMinutes(20)), grant.purgeAt());
    }

    @Test
    void aPendingGrantIsAuthorizationPendingAndPollingTooSoonSlowsItDown() {
        DeviceGrant.Polled first = grant.poll(NOW.plusSeconds(5), "aps_unused");
        assertEquals(DeviceGrant.Outcome.AUTHORIZATION_PENDING, first.outcome());
        assertEquals(NOW.plusSeconds(5), first.next().lastPolledAt());
        assertEquals(2, first.next().version());

        DeviceGrant.Polled tooSoon = first.next().poll(NOW.plusSeconds(9), "aps_unused");
        assertEquals(DeviceGrant.Outcome.SLOW_DOWN, tooSoon.outcome());
        assertEquals(10, tooSoon.next().interval(), "slow_down adds five seconds");

        // the longer interval holds for every later poll
        DeviceGrant.Polled stillTooSoon = tooSoon.next().poll(NOW.plusSeconds(17), "aps_unused");
        assertEquals(DeviceGrant.Outcome.SLOW_DOWN, stillTooSoon.outcome());
        assertEquals(15, stillTooSoon.next().interval());

        DeviceGrant.Polled patient = stillTooSoon.next().poll(NOW.plusSeconds(32), "aps_unused");
        assertEquals(DeviceGrant.Outcome.AUTHORIZATION_PENDING, patient.outcome());
        assertEquals(15, patient.next().interval());
    }

    @Test
    void anApprovedGrantIsRedeemedOnceForTheSessionItNames() {
        DeviceGrant approved = grant.approve(DeviceGrant.Approver.of(gonzalo), NOW.plusSeconds(20));
        assertEquals("approved", approved.statusAt(NOW.plusSeconds(21)));
        assertEquals(NOW.plusSeconds(20), approved.decidedAt());
        assertEquals("user_gonzalo", approved.approver().user().userId());
        assertEquals(gonzalo.authTime(), approved.approver().authTime());

        DeviceGrant.Polled redeemed = approved.poll(NOW.plusSeconds(30), "aps_01jabcdefghjkmnpqrstvwxyz0");
        assertEquals(DeviceGrant.Outcome.REDEEMED, redeemed.outcome());
        assertEquals(NOW.plusSeconds(30), redeemed.next().redeemedAt());
        assertEquals("aps_01jabcdefghjkmnpqrstvwxyz0", redeemed.next().apiSessionId());

        DeviceGrant.Polled again = redeemed.next().poll(NOW.plusSeconds(40), "aps_other");
        assertEquals(DeviceGrant.Outcome.ALREADY_REDEEMED, again.outcome());
        assertNull(again.next(), "a refused poll changes nothing");
    }

    @Test
    void aDeniedGrantIsAccessDeniedAndAnExpiredOneExpiredToken() {
        DeviceGrant denied = grant.deny(NOW.plusSeconds(20));
        assertEquals("denied", denied.statusAt(NOW.plusSeconds(21)));
        assertNull(denied.approver());
        assertEquals(DeviceGrant.Outcome.ACCESS_DENIED, denied.poll(NOW.plusSeconds(30), "aps_unused").outcome());

        assertEquals(DeviceGrant.Outcome.EXPIRED_TOKEN, grant.poll(NOW.plus(Duration.ofMinutes(10)), "aps_unused").outcome());
        DeviceGrant approved = grant.approve(DeviceGrant.Approver.of(gonzalo), NOW.plusSeconds(20));
        assertEquals(DeviceGrant.Outcome.EXPIRED_TOKEN, approved.poll(NOW.plus(Duration.ofMinutes(11)), "aps_unused").outcome(),
                "an approved code not redeemed in time is expired");
    }

    @Test
    void aGrantIsDecidedOnceAndNeverAfterItExpired() {
        DeviceGrant approved = grant.approve(DeviceGrant.Approver.of(gonzalo), NOW.plusSeconds(20));
        DeviceGrantDecidedException twice = assertThrows(DeviceGrantDecidedException.class,
                () -> approved.approve(DeviceGrant.Approver.of(gonzalo), NOW.plusSeconds(25)));
        assertEquals(DeviceGrantDecidedException.CODE, twice.code());
        assertEquals("approved", twice.detail().get("status"));
        assertThrows(DeviceGrantDecidedException.class, () -> approved.deny(NOW.plusSeconds(25)));
        assertThrows(DeviceGrantDecidedException.class, () -> grant.deny(NOW).approve(DeviceGrant.Approver.of(gonzalo), NOW));

        DeviceGrantExpiredException late = assertThrows(DeviceGrantExpiredException.class,
                () -> grant.approve(DeviceGrant.Approver.of(gonzalo), NOW.plus(Duration.ofMinutes(10))));
        assertEquals(grant.expiresAt(), late.expiresAt());
        assertThrows(DeviceGrantExpiredException.class, () -> grant.deny(NOW.plus(Duration.ofMinutes(12))));
    }

    @Test
    void theApproverKeepsThePersonNotTheBrowserSessionsId() {
        DeviceGrant.Approver by = DeviceGrant.Approver.of(gonzalo);
        assertEquals("gonzalo", by.subject());
        assertEquals(COMPUTE, by.accountId());
        assertEquals("global", by.region());
        assertFalse(by.toString().contains(gonzalo.id()), "the cookie's value is never kept in a grant");
    }

    @Test
    void whereARequestCameFromIsCleaned() {
        RequestedFrom from = new RequestedFrom(null, "agent\u0000with\ncontrol" + "x".repeat(400));
        assertEquals("unknown", from.ip());
        assertEquals(RequestedFrom.MAX_USER_AGENT, from.userAgent().length());
        assertFalse(from.userAgent().contains("\n"));
        assertNull(new RequestedFrom("10.0.0.1", "  ").userAgent());
    }

    @Test
    void secretsAreLongRandomAndKeptAsTheirHashes() {
        String one = Secrets.generate();
        String two = Secrets.generate();
        assertEquals(43, one.length());
        assertTrue(one.matches("^[A-Za-z0-9_-]{43}$"), one);
        assertFalse(one.equals(two));
        assertEquals(Secrets.hash(one), Secrets.hash(one));
        assertFalse(Secrets.hash(one).equals(Secrets.hash(two)));
        assertFalse(Secrets.hash(one).contains(one));
        assertTrue(Secrets.sameHash(Secrets.hash(one), Secrets.hash(one)));
        assertFalse(Secrets.sameHash(Secrets.hash(one), Secrets.hash(two)));
        assertFalse(Secrets.sameHash(null, Secrets.hash(two)));
    }
}
