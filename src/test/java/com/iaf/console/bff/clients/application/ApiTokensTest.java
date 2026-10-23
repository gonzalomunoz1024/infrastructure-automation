package com.iaf.console.bff.clients.application;

import static com.iaf.console.bff.clients.application.Fixtures.CLI;
import static com.iaf.console.bff.clients.application.Fixtures.COMPUTE;
import static com.iaf.console.bff.clients.application.Fixtures.DATA;
import static com.iaf.console.bff.clients.application.Fixtures.GONZALO_DATA;
import static com.iaf.console.bff.clients.application.Fixtures.NOW;
import static com.iaf.console.bff.clients.application.Fixtures.refused;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.iaf.console.bff.clients.domain.ApiSession;
import com.iaf.console.bff.clients.domain.DeviceGrant;
import com.iaf.console.bff.clients.domain.DeviceGrantExpiredException;
import com.iaf.console.bff.clients.domain.InvalidBearerTokenException;
import com.iaf.console.bff.clients.domain.RequestedFrom;
import com.iaf.console.bff.clients.domain.Secrets;
import com.iaf.console.bff.clients.domain.UserCode;
import com.iaf.console.bff.session.domain.Principal;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.bff.trust.domain.ApiTokenCheck;
import com.iaf.console.kit.errors.NotFoundException;

/**
 * The token endpoint's rules over memory with a moving clock: the device grant's polling answers,
 * one redemption per device code, refresh tokens that rotate and end their session when one comes
 * back, the memberships read again on every refresh, the session's twelve and eight hours, and
 * the revoke endpoint.
 */
class ApiTokensTest {

    private final Fixtures f = new Fixtures();

    @Test
    void aDeviceSignInRunsFromTheCodesToTheTokens() {
        DeviceAuthorization.Started started = f.authorization.start("console-cli", CLI).block();
        assertEquals(43, started.deviceCode().length());
        assertTrue(started.userCode().matches("^[BCDFGHJKLMNPQRSTVWXZ]{4}-[BCDFGHJKLMNPQRSTVWXZ]{4}$"), started.userCode());
        assertEquals("http://localhost:5173/#/device", started.verificationUri());
        assertEquals("http://localhost:5173/#/device?code=" + started.userCode(), started.verificationUriComplete());
        assertEquals(600, started.expiresIn());
        assertEquals(5, started.interval());

        refused(400, "authorization_pending", () -> f.poll("console-cli", started.deviceCode()));
        DeviceGrant shown = f.authorization.find(started.userCode().replace("-", "").toLowerCase()).block();
        assertEquals("pending", f.authorization.statusOf(shown));
        assertEquals("10.0.0.7", shown.requestedFrom().ip());
        assertEquals("console-cli/1.0 (linux)", shown.requestedFrom().userAgent());

        Session gonzalo = f.browser("gonzalo", NOW.minusSeconds(30));
        f.clock.advance(Duration.ofSeconds(3));
        DeviceGrant approved = f.authorization.approve(gonzalo, started.userCode()).block();
        assertEquals("approved", f.authorization.statusOf(approved));

        f.clock.advance(Duration.ofSeconds(5));
        ApiTokens.Issued issued = f.poll("console-cli", started.deviceCode());
        assertEquals(900, issued.expiresIn());
        assertEquals(Duration.ofHours(12).minusSeconds(5).toSeconds(), issued.refreshExpiresIn(), "twelve hours from the approval");
        assertNotEquals(issued.accessToken(), issued.refreshToken());

        ApiTokenCheck check = f.trust.verifyApiAccessToken(issued.accessToken());
        assertTrue(check.isValid(), check.refusal());
        assertEquals("gonzalo", check.token().subject());
        assertEquals(issued.sessionId(), check.token().sessionId());
        assertEquals("console-cli", check.token().clientId());
        assertEquals(gonzalo.authTime(), check.token().authTime(), "the approving sign-in");

        ApiSession stored = f.apiStore.find(issued.sessionId()).block();
        assertEquals(Secrets.hash(issued.refreshToken()), stored.refreshHash(), "only the refresh token's hash is kept");
        assertFalse(stored.toString().contains(issued.refreshToken()));
        DeviceGrant redeemed = f.grants.findByUserCode(UserCode.canonical(started.userCode()).orElseThrow(), f.clock.instant()).block();
        assertEquals(Secrets.hash(started.deviceCode()), redeemed.deviceCodeHash(), "only the device code's hash is kept");
        assertFalse(redeemed.toString().contains(started.deviceCode()));
        assertEquals(issued.sessionId(), redeemed.apiSessionId());
        assertEquals(COMPUTE, stored.accountId());
        assertEquals("global", stored.region());
    }

    @Test
    void pollingSoonerThanTheIntervalIsSlowDownAndLengthensIt() {
        DeviceAuthorization.Started started = f.authorization.start("console-cli", CLI).block();
        refused(400, "authorization_pending", () -> f.poll("console-cli", started.deviceCode()));
        f.clock.advance(Duration.ofSeconds(2));
        assertTrue(refused(400, "slow_down", () -> f.poll("console-cli", started.deviceCode())).getMessage().contains("10 seconds"));
        f.clock.advance(Duration.ofSeconds(9));
        assertTrue(refused(400, "slow_down", () -> f.poll("console-cli", started.deviceCode())).getMessage().contains("15 seconds"));
        f.clock.advance(Duration.ofSeconds(15));
        refused(400, "authorization_pending", () -> f.poll("console-cli", started.deviceCode()));
    }

    @Test
    void aDeviceCodeIsRedeemedOnceAndBroughtBackItEndsTheSessionItOpened() {
        DeviceAuthorization.Started started = f.authorization.start("console-cli", CLI).block();
        f.authorization.approve(f.browser("gonzalo", NOW), started.userCode()).block();
        ApiTokens.Issued issued = f.poll("console-cli", started.deviceCode());
        assertNotNull(f.apiSessions.authenticate(issued.accessToken(), null, null).block());

        f.clock.advance(Duration.ofSeconds(10));
        refused(400, "invalid_grant", () -> f.poll("console-cli", started.deviceCode()));
        assertNotNull(f.apiStore.find(issued.sessionId()).block().revokedAt(), "the session the code opened is revoked");
        assertThrows(InvalidBearerTokenException.class, () -> f.apiSessions.authenticate(issued.accessToken(), null, null).block());
        refused(400, "invalid_grant", () -> f.refresh("console-cli", issued.refreshToken()));
    }

    @Test
    void aDeniedGrantIsAccessDeniedAndAnExpiredOneExpiredTokenThenUnknown() {
        DeviceAuthorization.Started denied = f.authorization.start("console-cli", CLI).block();
        f.authorization.deny(f.browser("gonzalo", NOW), denied.userCode()).block();
        refused(400, "access_denied", () -> f.poll("console-cli", denied.deviceCode()));

        DeviceAuthorization.Started late = f.authorization.start("console-sdk", CLI).block();
        f.clock.advance(Duration.ofMinutes(10));
        refused(400, "expired_token", () -> f.poll("console-sdk", late.deviceCode()));
        assertEquals("expired", f.authorization.statusOf(f.authorization.find(late.userCode()).block()));
        assertThrows(DeviceGrantExpiredException.class, () -> f.authorization.approve(f.browser("gonzalo", f.clock.instant()), late.userCode()).block());

        f.clock.advance(Duration.ofMinutes(10));
        refused(400, "invalid_grant", () -> f.poll("console-sdk", late.deviceCode()));
        assertThrows(NotFoundException.class, () -> f.authorization.find(late.userCode()).block(), "ten minutes past its expiry a code is unknown");
    }

    @Test
    void theClientIsOneOfTheConsolesAndTheOneTheCodeWasIssuedTo() {
        refused(400, "invalid_client", () -> f.authorization.start("someone-else", CLI).block());
        refused(400, "invalid_client", () -> f.authorization.start(null, CLI).block());
        DeviceAuthorization.Started started = f.authorization.start("console-sdk", CLI).block();
        refused(401, "invalid_client", () -> f.poll("console-cli", started.deviceCode()));
        refused(401, "invalid_client", () -> f.poll("someone-else", started.deviceCode()));
        refused(400, "invalid_request", () -> f.poll(null, started.deviceCode()));
        refused(400, "invalid_request", () -> f.poll("console-sdk", null));
        refused(400, "invalid_grant", () -> f.poll("console-sdk", Secrets.generate()));
        refused(400, "invalid_request", () -> f.tokens.exchange(new ApiTokens.TokenRequest(null, "console-sdk", started.deviceCode(), null), CLI).block());
        refused(400, "unsupported_grant_type",
                () -> f.tokens.exchange(new ApiTokens.TokenRequest("client_credentials", "console-sdk", null, null), CLI).block());
        refused(400, "invalid_request", () -> f.refresh("console-sdk", null));

        ApiTokens.Issued issued = f.signedIn("gonzalo", "console-sdk");
        refused(401, "invalid_client", () -> f.refresh("console-cli", issued.refreshToken()));
        assertNull(f.apiStore.find(issued.sessionId()).block().revokedAt(), "another client's refusal ends nothing");
    }

    @Test
    void aRefreshRotatesTheTokenAndARotatedOneBroughtBackEndsTheSession() {
        ApiTokens.Issued first = f.signedIn("gonzalo", "console-cli");
        f.clock.advance(Duration.ofMinutes(14));
        ApiTokens.Issued second = f.refresh("console-cli", first.refreshToken());
        assertNotEquals(first.refreshToken(), second.refreshToken());
        assertNotEquals(first.accessToken(), second.accessToken());
        assertEquals(first.sessionId(), second.sessionId());
        assertEquals(900, second.expiresIn());
        assertEquals(first.refreshExpiresIn() - Duration.ofMinutes(14).toSeconds(), second.refreshExpiresIn(), "the end does not move");
        ApiSession rotated = f.apiStore.find(first.sessionId()).block();
        assertEquals(f.clock.instant(), rotated.lastUsedAt());
        assertTrue(rotated.rotatedAway(Secrets.hash(first.refreshToken())));
        ApiTokens.Issued third = f.refresh("console-cli", second.refreshToken());

        // the first token again: someone holds a copy, so the session ends for everyone
        refused(400, "invalid_grant", () -> f.refresh("console-cli", first.refreshToken()));
        ApiSession ended = f.apiStore.find(first.sessionId()).block();
        assertNotNull(ended.revokedAt());
        assertEquals("a rotated refresh token was presented again", ended.revokedBecause());
        refused(400, "invalid_grant", () -> f.refresh("console-cli", third.refreshToken()));
        assertThrows(InvalidBearerTokenException.class, () -> f.apiSessions.authenticate(third.accessToken(), null, null).block(),
                "this instance drops its copy at once");
    }

    @Test
    void aRefreshReadsThePersonsMembershipsAgain() {
        ApiTokens.Issued first = f.signedIn("gonzalo", "console-cli");
        f.resolver.asked.clear();
        f.resolver.answer("gonzalo", new Principal("user_gonzalo", "gonzalo@example.com", "Gonzalo M.", List.of("owners")), GONZALO_DATA);
        ApiTokens.Issued second = f.refresh("console-cli", first.refreshToken());
        assertEquals(1, f.resolver.asked.size());
        assertEquals(Fixtures.ISSUER, f.resolver.asked.get(0).issuer());
        assertEquals("gonzalo", f.resolver.asked.get(0).subject());
        ApiSession refreshed = f.apiStore.find(first.sessionId()).block();
        assertEquals(List.of(GONZALO_DATA), refreshed.accounts());
        assertEquals(DATA, refreshed.accountId(), "the person left the compute account");
        assertEquals("Gonzalo M.", refreshed.user().name());
        Session call = f.apiSessions.authenticate(second.accessToken(), null, null).block();
        assertEquals(DATA, call.accountId());
        assertEquals("eu-west", call.region());

        f.resolver.answers.remove("gonzalo");
        refused(400, "invalid_grant", () -> f.refresh("console-cli", second.refreshToken()));
        assertEquals("the person belongs to no console account any more", f.apiStore.find(first.sessionId()).block().revokedBecause());
    }

    @Test
    void aSessionEndsTwelveHoursAfterItsApprovalOrAfterEightWithoutARefresh() {
        ApiTokens.Issued first = f.signedIn("gonzalo", "console-cli");
        f.clock.advance(Duration.ofHours(7));
        ApiTokens.Issued second = f.refresh("console-cli", first.refreshToken());
        f.clock.advance(Duration.ofHours(4).plusMinutes(58));
        ApiTokens.Issued last = f.refresh("console-cli", second.refreshToken());
        assertTrue(last.expiresIn() < 900 && last.expiresIn() > 0, "an access token ends with its session: " + last.expiresIn());
        assertEquals(last.refreshExpiresIn(), last.expiresIn());
        f.clock.advance(Duration.ofMinutes(5));
        refused(400, "invalid_grant", () -> f.refresh("console-cli", last.refreshToken()));
        assertThrows(InvalidBearerTokenException.class, () -> f.apiSessions.authenticate(last.accessToken(), null, null).block());

        ApiTokens.Issued idle = f.signedIn("gonzalo", "console-cli");
        f.clock.advance(Duration.ofHours(8));
        refused(400, "invalid_grant", () -> f.refresh("console-cli", idle.refreshToken()));
    }

    @Test
    void theRevokeEndpointEndsTheSessionWhateverTheTokenAndSaysNothing() {
        ApiTokens.Issued issued = f.signedIn("gonzalo", "console-cli");
        f.tokens.revoke("not-a-token").block();
        f.tokens.revoke(null).block();
        assertNull(f.apiStore.find(issued.sessionId()).block().revokedAt());
        f.tokens.revoke(issued.refreshToken()).block();
        assertEquals("signed out", f.apiStore.find(issued.sessionId()).block().revokedBecause());
        refused(400, "invalid_grant", () -> f.refresh("console-cli", issued.refreshToken()));
        f.tokens.revoke(issued.refreshToken()).block();

        ApiTokens.Issued other = f.signedIn("mia", "console-sdk");
        ApiTokens.Issued next = f.refresh("console-sdk", other.refreshToken());
        f.tokens.revoke(other.refreshToken()).block();
        assertNotNull(f.apiStore.find(next.sessionId()).block().revokedAt(), "a rotated token signs out too");
    }

    @Test
    void tooManySignInsFromOneAddressInAMinuteIsSlowDown() {
        for (int i = 0; i < 10; i++) {
            f.authorization.start("console-cli", CLI).block();
        }
        refused(429, "slow_down", () -> f.authorization.start("console-cli", CLI).block());
        assertNotNull(f.authorization.start("console-cli", new RequestedFrom("10.0.0.8", "console-cli/1.0")).block(), "another address");
        f.clock.advance(Duration.ofSeconds(61));
        assertNotNull(f.authorization.start("console-cli", CLI).block(), "a minute later");
    }
}
