package com.iaf.console.bff.clients.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.iaf.console.bff.session.domain.AccountMembership;
import com.iaf.console.bff.session.domain.Principal;
import com.iaf.console.bff.session.domain.Session;

/** An API session: where it opens, how its refresh token rotates and remembers the old ones, how it ends, and the session of a bearer call. */
class ApiSessionTest {

    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");
    private static final String COMPUTE = "123456789012";
    private static final String DATA = "210987654321";
    private static final Duration TTL = Duration.ofHours(12);
    private static final Duration IDLE = Duration.ofHours(8);

    private final Principal person = new Principal("user_gonzalo", "gonzalo@example.com", "Gonzalo Munoz", List.of("owners"));
    private final AccountMembership compute = new AccountMembership(COMPUTE, "Compute", "active", "global", List.of("owners"), List.of("account-admin"));
    private final AccountMembership data = new AccountMembership(DATA, "Data", "active", "eu-west", List.of("owners"), List.of("account-admin"));
    private final Session browser = new Session("cookie-session-id", person, List.of(compute, data), COMPUTE, "global", NOW.minus(Duration.ofHours(1)),
            NOW.minusSeconds(40), NOW, NOW.plus(Duration.ofHours(11)), "http://idp", "gonzalo");

    private ApiSession opened(String refreshHash) {
        DeviceGrant grant = DeviceGrant.requested("BCDFGHJK", Secrets.hash("device-code"), "console-cli", new RequestedFrom("10.0.0.7", "cli"),
                NOW.minusSeconds(60), Duration.ofMinutes(10), Duration.ofSeconds(5));
        DeviceGrant approved = grant.approve(DeviceGrant.Approver.of(browser), NOW.minusSeconds(30));
        DeviceGrant redeemed = approved.poll(NOW, ApiSession.newId()).next();
        return ApiSession.open(redeemed, refreshHash, new RequestedFrom("10.0.0.8", "console-cli/1.0"), NOW, TTL, IDLE);
    }

    @Test
    void aRedeemedGrantOpensASessionAsItsApproverWas() {
        ApiSession api = opened("hash-1");
        assertTrue(ApiSession.isId(api.id()), api.id());
        assertTrue(api.id().matches("^aps_[0-9a-z]{26}$"), api.id());
        assertEquals("console-cli", api.clientId());
        assertEquals("gonzalo", api.subject());
        assertEquals(COMPUTE, api.accountId());
        assertEquals("global", api.region());
        assertEquals(browser.authTime(), api.authTime(), "the approving sign-in");
        assertEquals(NOW.minusSeconds(30).plus(TTL), api.expiresAt(), "twelve hours from the approval");
        assertEquals(NOW.plus(IDLE), api.idleExpiresAt());
        assertEquals("10.0.0.8", api.ip());
        assertEquals("console-cli/1.0", api.userAgent());
        assertTrue(api.openAt(NOW));
        assertFalse(ApiSession.isId("aps_01JABCDEFGHJKMNPQRSTVWXYZ0"), "uppercase is not an id this service mints");
        assertFalse(ApiSession.isId("cookie-session-id"));
    }

    @Test
    void aRefreshRotatesTheTokenAndRemembersTheOldOne() {
        ApiSession api = opened("hash-1");
        ApiSession once = api.rotated("hash-2", person, List.of(compute, data), NOW.plus(Duration.ofHours(1)), IDLE);
        assertEquals("hash-2", once.refreshHash());
        assertTrue(once.rotatedAway("hash-1"));
        assertFalse(once.rotatedAway("hash-2"), "the current token is not a rotated one");
        assertFalse(once.rotatedAway("hash-3"));
        assertEquals(api.version() + 1, once.version());
        assertEquals(NOW.plus(Duration.ofHours(1)), once.lastUsedAt());
        assertEquals(NOW.plus(Duration.ofHours(9)), once.idleExpiresAt(), "idle from the last refresh");
        assertEquals(api.expiresAt(), once.expiresAt(), "the absolute end never moves");
        ApiSession twice = once.rotated("hash-3", person, List.of(compute, data), NOW.plus(Duration.ofHours(2)), IDLE);
        assertTrue(twice.rotatedAway("hash-1") && twice.rotatedAway("hash-2"));
        assertEquals(NOW.plus(Duration.ofHours(10)), twice.idleExpiresAt());
    }

    @Test
    void theRotatedTokensRememberedAreCapped() {
        ApiSession api = opened("hash-0");
        for (int i = 1; i <= ApiSession.MAX_ROTATED + 5; i++) {
            api = api.rotated("hash-" + i, person, List.of(compute), NOW, IDLE);
        }
        assertEquals(ApiSession.MAX_ROTATED, api.rotatedHashes().size());
        assertFalse(api.rotatedAway("hash-0"), "the oldest are forgotten");
        assertTrue(api.rotatedAway("hash-" + (ApiSession.MAX_ROTATED + 4)));
    }

    @Test
    void aRefreshKeepsTheAccountWhileThePersonIsInItElseTheFirstOne() {
        ApiSession api = opened("hash-1");
        assertEquals(COMPUTE, api.rotated("hash-2", person, List.of(data, compute), NOW, IDLE).accountId());
        ApiSession moved = api.rotated("hash-2", person, List.of(data), NOW, IDLE);
        assertEquals(DATA, moved.accountId());
        assertEquals("eu-west", moved.region(), "the account's default region");
        assertEquals(List.of(data), moved.accounts());
        assertThrows(IllegalArgumentException.class, () -> api.rotated("hash-2", person, List.of(), NOW, IDLE));
    }

    @Test
    void aSessionEndsWhenRevokedPastItsTwelveHoursOrIdleForEight() {
        ApiSession api = opened("hash-1");
        assertNull(api.endedAt(NOW));
        assertEquals("idle too long", api.endedAt(NOW.plus(IDLE)));
        ApiSession refreshed = api.rotated("hash-2", person, List.of(compute), NOW.plus(Duration.ofHours(7)), IDLE);
        assertTrue(refreshed.openAt(NOW.plus(Duration.ofHours(11))));
        assertEquals("expired", refreshed.endedAt(api.expiresAt()));
        assertEquals(api.expiresAt(), refreshed.purgeAt(), "the absolute end comes first");

        ApiSession revoked = api.revoked(NOW.plusSeconds(5), "signed out");
        assertFalse(revoked.openAt(NOW.plusSeconds(6)));
        assertEquals("revoked (signed out)", revoked.endedAt(NOW.plusSeconds(6)));
        assertSame(revoked, revoked.revoked(NOW.plusSeconds(9), "again"), "a revoked session stays as it was");
    }

    @Test
    void aBearerCallsSessionIsThePersonInTheAccountItNamesSignedInAtTheApproval() {
        ApiSession api = opened("hash-1");
        Session call = api.callSession(DATA, "eu-west", api.authTime(), NOW.plusSeconds(3));
        assertEquals(api.id(), call.id(), "the API session's id, never a cookie's");
        assertEquals(DATA, call.accountId());
        assertEquals("eu-west", call.region());
        assertEquals(api.authTime(), call.authTime());
        assertEquals("user_gonzalo", call.user().userId());
        assertEquals(api.expiresAt(), call.expiresAt());
        assertEquals("gonzalo", call.subject());
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            ids.add(ApiSession.newId());
        }
        assertEquals(50, ids.stream().distinct().count());
    }
}
