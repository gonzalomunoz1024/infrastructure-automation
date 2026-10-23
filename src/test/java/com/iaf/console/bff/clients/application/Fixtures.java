package com.iaf.console.bff.clients.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.iaf.console.bff.clients.adapters.outbound.InMemoryApiSessionStore;
import com.iaf.console.bff.clients.adapters.outbound.InMemoryDeviceGrantStore;
import com.iaf.console.bff.clients.domain.OAuthException;
import com.iaf.console.bff.clients.domain.RequestedFrom;
import com.iaf.console.bff.session.adapters.outbound.InMemorySessionStore;
import com.iaf.console.bff.session.application.SessionProperties;
import com.iaf.console.bff.session.application.Sessions;
import com.iaf.console.bff.session.domain.AccountMembership;
import com.iaf.console.bff.session.domain.CsrfTokens;
import com.iaf.console.bff.session.domain.IdpUser;
import com.iaf.console.bff.session.domain.NoConsoleAccountException;
import com.iaf.console.bff.session.domain.Principal;
import com.iaf.console.bff.session.domain.ResolvedPrincipal;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.bff.session.domain.StepUpPolicy;
import com.iaf.console.bff.session.ports.outbound.PrincipalResolverPort;
import com.iaf.console.bff.trust.application.Trust;
import com.iaf.console.bff.trust.application.TrustProperties;
import com.iaf.console.bff.trust.ports.outbound.SigningKeyPort;
import com.iaf.console.platform.assertion.OnBehalfOfAssertions;
import com.nimbusds.jose.jwk.ECKey;

import reactor.core.publisher.Mono;

/**
 * The CLI and SDK sign-in wired over memory with a clock the test moves: the demo's people
 * (gonzalo an admin of both accounts, mia a viewer of the compute one) as the identity service
 * would resolve them, a fresh signing key, and the default settings.
 */
final class Fixtures {

    static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");
    static final String COMPUTE = "123456789012";
    static final String DATA = "210987654321";
    static final String ISSUER = "http://localhost/demo-idp";
    static final RequestedFrom CLI = new RequestedFrom("10.0.0.7", "console-cli/1.0 (linux)");

    static final AccountMembership GONZALO_COMPUTE = new AccountMembership(COMPUTE, "Compute", "active", "global", List.of("owners"),
            List.of("account-admin"));
    static final AccountMembership GONZALO_DATA = new AccountMembership(DATA, "Data", "active", "eu-west", List.of("owners"), List.of("account-admin"));
    static final AccountMembership MIA_COMPUTE = new AccountMembership(COMPUTE, "Compute", "active", "global", List.of("viewers"), List.of("viewer"));

    final MovingClock clock = new MovingClock(NOW);
    final ClientsProperties properties = new ClientsProperties("http://localhost:5173/#/device", Duration.ofMinutes(10), Duration.ofSeconds(5), 10,
            Duration.ofMinutes(15), Duration.ofHours(12), Duration.ofHours(8), Duration.ofMinutes(1), 0);
    final InMemoryDeviceGrantStore grants = new InMemoryDeviceGrantStore();
    final InMemoryApiSessionStore apiStore = new InMemoryApiSessionStore(clock);
    final InMemorySessionStore browserStore = new InMemorySessionStore(clock);
    final FakeResolver resolver = new FakeResolver();
    final ECKey key = OnBehalfOfAssertions.generateKey("bff-test-1");
    final Trust trust = new Trust(new SigningKeyPort() {
        @Override
        public ECKey currentKey() {
            return key;
        }
    }, new TrustProperties("console-bff", "platform", Duration.ofSeconds(60), Duration.ofSeconds(30), null, null, Duration.ofMinutes(5),
            Duration.ofMinutes(5), null, Map.of()), clock);
    final Sessions sessions = new Sessions(browserStore, new CsrfTokens(new byte[32]), new StepUpPolicy(Duration.ofMinutes(10), List.of("*:delete")),
            () -> Mono.just(List.of("global", "eu-west")), new SessionProperties("console_session", "console_login", Duration.ofHours(12),
                    Duration.ofHours(1), Duration.ofMinutes(10), false, "Lax", "/", null), clock);
    final DeviceAuthorization authorization = new DeviceAuthorization(grants, sessions, properties, clock);
    final ApiSessions apiSessions = new ApiSessions(apiStore, trust, sessions, properties, clock);
    final ApiTokens tokens = new ApiTokens(grants, apiStore, apiSessions, resolver, trust, properties, clock);

    Fixtures() {
        resolver.answer("gonzalo", new Principal("user_gonzalo", "gonzalo@example.com", "Gonzalo Munoz", List.of("owners")), GONZALO_COMPUTE, GONZALO_DATA);
        resolver.answer("mia", new Principal("user_mia", "mia@example.com", "Mia", List.of("viewers")), MIA_COMPUTE);
    }

    /** A person's browser session in their first account, signed in at {@code authTime}. */
    Session browser(String subject, Instant authTime) {
        ResolvedPrincipal resolved = resolver.answers.get(subject);
        AccountMembership first = resolved.accounts().get(0);
        return new Session("cookie-" + subject + "-" + authTime.toEpochMilli(), resolved.user(), resolved.accounts(), first.accountId(),
                first.defaultRegion(), authTime, authTime, clock.instant(), clock.instant().plus(Duration.ofHours(12)), ISSUER, subject);
    }

    /** A CLI signed in as a person: started, approved by their fresh browser session, polled after the interval. */
    ApiTokens.Issued signedIn(String subject, String clientId) {
        return signedIn(subject, clientId, browser(subject, clock.instant().minusSeconds(30)));
    }

    ApiTokens.Issued signedIn(String subject, String clientId, Session approver) {
        DeviceAuthorization.Started started = authorization.start(clientId, CLI).block();
        authorization.approve(approver, started.userCode()).block();
        return tokens.exchange(new ApiTokens.TokenRequest(ApiTokens.DEVICE_CODE_GRANT, clientId, started.deviceCode(), null), CLI).block();
    }

    ApiTokens.Issued poll(String clientId, String deviceCode) {
        return tokens.exchange(new ApiTokens.TokenRequest(ApiTokens.DEVICE_CODE_GRANT, clientId, deviceCode, null), CLI).block();
    }

    ApiTokens.Issued refresh(String clientId, String refreshToken) {
        return tokens.exchange(new ApiTokens.TokenRequest(ApiTokens.REFRESH_GRANT, clientId, null, refreshToken), CLI).block();
    }

    /** Runs a token call that must be refused, and answers the refusal. */
    static OAuthException refused(int status, String error, Runnable call) {
        OAuthException refusal = assertThrows(OAuthException.class, call::run);
        assertEquals(error, refusal.error(), refusal.getMessage());
        assertEquals(status, refusal.status(), refusal.getMessage());
        return refusal;
    }

    /** The identity service's principals:resolve, as the test sets it; every question it was asked. */
    static final class FakeResolver implements PrincipalResolverPort {

        final Map<String, ResolvedPrincipal> answers = new HashMap<>();
        final List<IdpUser> asked = new ArrayList<>();

        void answer(String subject, Principal person, AccountMembership... accounts) {
            answers.put(subject, new ResolvedPrincipal(person, List.of(accounts)));
        }

        @Override
        public Mono<ResolvedPrincipal> resolve(IdpUser user) {
            asked.add(user);
            ResolvedPrincipal answer = answers.get(user.subject());
            return answer == null || !answer.hasAccounts() ? Mono.error(new NoConsoleAccountException(user.subject())) : Mono.just(answer);
        }
    }

    /** A clock the test moves. */
    static final class MovingClock extends Clock {

        private Instant now;

        MovingClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
