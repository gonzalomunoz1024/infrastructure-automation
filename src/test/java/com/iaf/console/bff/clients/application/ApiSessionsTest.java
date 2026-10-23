package com.iaf.console.bff.clients.application;

import static com.iaf.console.bff.clients.application.Fixtures.COMPUTE;
import static com.iaf.console.bff.clients.application.Fixtures.DATA;
import static com.iaf.console.bff.clients.application.Fixtures.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.iaf.console.bff.clients.domain.ApiSession;
import com.iaf.console.bff.clients.domain.InvalidBearerTokenException;
import com.iaf.console.bff.clients.domain.NotMemberException;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.bff.session.domain.StepUpRequiredException;
import com.iaf.console.bff.trust.domain.ApiAccessToken;
import com.iaf.console.bff.trust.domain.InternalToken;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.paging.Page;

/**
 * A bearer call's session (the person, the account and region its headers name, the approving
 * sign-in as its step-up time, the API session checked at most once a minute), and the person's
 * list of API sessions with their revoke.
 */
class ApiSessionsTest {

    private final Fixtures f = new Fixtures();

    @Test
    void aBearerCallIsThePersonInTheApiSessionsAccountAndRegion() {
        ApiTokens.Issued issued = f.signedIn("gonzalo", "console-cli");
        Session call = f.apiSessions.authenticate(issued.accessToken(), null, " ").block();
        assertEquals(issued.sessionId(), call.id());
        assertEquals("user_gonzalo", call.user().userId());
        assertEquals(COMPUTE, call.accountId());
        assertEquals("global", call.region());
        assertEquals(NOW.minusSeconds(30), call.authTime(), "the browser sign-in that approved the grant");
        assertEquals(2, call.accounts().size());
    }

    @Test
    void theHeadersNameAnotherOfThePersonsAccountsOrRegions() {
        ApiTokens.Issued issued = f.signedIn("gonzalo", "console-cli");
        Session data = f.apiSessions.authenticate(issued.accessToken(), DATA, null).block();
        assertEquals(DATA, data.accountId());
        assertEquals("eu-west", data.region(), "the account's default region");
        Session region = f.apiSessions.authenticate(issued.accessToken(), null, "eu-west").block();
        assertEquals(COMPUTE, region.accountId());
        assertEquals("eu-west", region.region());
        assertEquals("global", f.apiSessions.authenticate(issued.accessToken(), DATA, "global").block().region());

        NotMemberException stranger = assertThrows(NotMemberException.class,
                () -> f.apiSessions.authenticate(issued.accessToken(), "999999999999", null).block());
        assertEquals("999999999999", stranger.accountId());
        assertThrows(IllegalArgumentException.class, () -> f.apiSessions.authenticate(issued.accessToken(), null, "mars-1").block());
        assertThrows(IllegalArgumentException.class, () -> f.apiSessions.authenticate(issued.accessToken(), null, "EU WEST").block());

        ApiTokens.Issued mia = f.signedIn("mia", "console-cli");
        assertEquals(COMPUTE, f.apiSessions.authenticate(mia.accessToken(), COMPUTE, null).block().accountId());
        assertThrows(NotMemberException.class, () -> f.apiSessions.authenticate(mia.accessToken(), DATA, null).block(), "mia is in the compute account only");
    }

    @Test
    void aTokenIsRefusedOnceItsSessionEndsOrWhenItIsNoAccessToken() {
        ApiTokens.Issued issued = f.signedIn("gonzalo", "console-cli");
        assertNotNull(f.apiSessions.authenticate(issued.accessToken(), null, null).block());

        // revoked on another instance: this one sees it at its next check, within a minute
        ApiSession open = f.apiStore.find(issued.sessionId()).block();
        f.apiStore.update(open.revoked(f.clock.instant(), "revoked elsewhere"), open.version()).block();
        f.clock.advance(Duration.ofSeconds(30));
        assertNotNull(f.apiSessions.authenticate(issued.accessToken(), null, null).block(), "the copy is less than a minute old");
        f.clock.advance(Duration.ofSeconds(31));
        InvalidBearerTokenException ended = assertThrows(InvalidBearerTokenException.class,
                () -> f.apiSessions.authenticate(issued.accessToken(), null, null).block());
        assertEquals("its API session ended: revoked (revoked elsewhere)", ended.reason());

        assertThrows(InvalidBearerTokenException.class, () -> f.apiSessions.authenticate("not.a.token", null, null).block());
        assertThrows(InvalidBearerTokenException.class, () -> f.apiSessions.authenticate("", null, null).block());
        String internal = f.trust.mint(InternalToken.user("console-identity-service", "user_gonzalo", COMPUTE, "global", List.of("owners"),
                List.of("account-admin"), null));
        assertEquals("not an access token (typ)",
                assertThrows(InvalidBearerTokenException.class, () -> f.apiSessions.authenticate(internal, null, null).block()).reason());
        String unknownSession = f.trust.signApiAccessToken(new ApiAccessToken("gonzalo", ApiSession.newId(), "console-cli", NOW, f.clock.instant(),
                f.clock.instant().plusSeconds(60), "jti-x"));
        assertEquals("its API session is unknown",
                assertThrows(InvalidBearerTokenException.class, () -> f.apiSessions.authenticate(unknownSession, null, null).block()).reason());
        ApiTokens.Issued other = f.signedIn("gonzalo", "console-cli");
        String otherClient = f.trust.signApiAccessToken(new ApiAccessToken("gonzalo", other.sessionId(), "console-sdk", NOW, f.clock.instant(),
                f.clock.instant().plusSeconds(60), "jti-y"));
        assertEquals("it does not match its API session",
                assertThrows(InvalidBearerTokenException.class, () -> f.apiSessions.authenticate(otherClient, null, null).block()).reason());
    }

    @Test
    void aBearerCallsStepUpReadsTheSignInThatApprovedIt() {
        Session approver = f.browser("gonzalo", NOW.minus(Duration.ofMinutes(9)));
        ApiTokens.Issued issued = f.signedIn("gonzalo", "console-cli", approver);
        Session call = f.apiSessions.authenticate(issued.accessToken(), null, null).block();
        f.sessions.requireFreshFor(call, "compute:delete").block();
        f.sessions.requireFreshFor(call, "compute:create").block();

        f.clock.advance(Duration.ofMinutes(2));
        Session later = f.apiSessions.authenticate(issued.accessToken(), null, null).block();
        StepUpRequiredException stale = assertThrows(StepUpRequiredException.class, () -> f.sessions.requireFreshFor(later, "compute:delete").block());
        assertEquals("compute:delete", stale.action());
        f.sessions.requireFreshFor(later, "compute:create").block();

        ApiTokens.Issued refreshed = f.refresh("console-cli", issued.refreshToken());
        Session afterRefresh = f.apiSessions.authenticate(refreshed.accessToken(), null, null).block();
        assertEquals(approver.authTime(), afterRefresh.authTime(), "a refresh is no sign-in");
    }

    @Test
    void aPersonListsTheirOpenSessionsNewestFirstAndRevokesTheirOwnOnly() {
        ApiTokens.Issued first = f.signedIn("gonzalo", "console-cli");
        f.clock.advance(Duration.ofMinutes(1));
        ApiTokens.Issued second = f.signedIn("gonzalo", "console-sdk");
        f.clock.advance(Duration.ofMinutes(1));
        ApiTokens.Issued third = f.signedIn("gonzalo", "console-cli");
        ApiTokens.Issued mias = f.signedIn("mia", "console-cli");
        Session gonzalo = f.browser("gonzalo", f.clock.instant());
        Session mia = f.browser("mia", f.clock.instant());

        Page<ApiSession> page = f.apiSessions.list(gonzalo, 2, null).block();
        assertEquals(List.of(third.sessionId(), second.sessionId()), page.items().stream().map(ApiSession::id).toList());
        assertNotNull(page.next());
        Page<ApiSession> rest = f.apiSessions.list(gonzalo, 2, page.next()).block();
        assertEquals(List.of(first.sessionId()), rest.items().stream().map(ApiSession::id).toList());
        assertNull(rest.next());
        assertEquals(List.of(mias.sessionId()), f.apiSessions.list(mia, null, null).block().items().stream().map(ApiSession::id).toList());

        assertThrows(NotFoundException.class, () -> f.apiSessions.revoke(mia, second.sessionId()).block(), "another person's is a 404");
        assertThrows(NotFoundException.class, () -> f.apiSessions.revoke(gonzalo, ApiSession.newId()).block());
        assertThrows(NotFoundException.class, () -> f.apiSessions.revoke(gonzalo, "not-an-id").block());
        f.apiSessions.revoke(gonzalo, second.sessionId()).block();
        assertEquals("revoked by the person", f.apiStore.find(second.sessionId()).block().revokedBecause());
        assertEquals(List.of(third.sessionId(), first.sessionId()), f.apiSessions.list(gonzalo, null, null).block().items().stream()
                .map(ApiSession::id).toList(), "the open ones only");
        assertThrows(InvalidBearerTokenException.class, () -> f.apiSessions.authenticate(second.accessToken(), null, null).block());
        f.apiSessions.revoke(gonzalo, second.sessionId()).block();

        f.clock.advance(Duration.ofHours(12));
        assertTrue(f.apiSessions.list(gonzalo, null, null).block().items().isEmpty(), "ended sessions are not listed");
        assertThrows(IllegalArgumentException.class, () -> f.apiSessions.list(gonzalo, null, "bm90LWEtY3Vyc29y").block());
        assertThrows(IllegalArgumentException.class, () -> f.apiSessions.list(gonzalo, 0, null).block());
    }
}
