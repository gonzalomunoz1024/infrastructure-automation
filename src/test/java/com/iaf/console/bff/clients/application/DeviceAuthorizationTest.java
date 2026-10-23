package com.iaf.console.bff.clients.application;

import static com.iaf.console.bff.clients.application.Fixtures.CLI;
import static com.iaf.console.bff.clients.application.Fixtures.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.iaf.console.bff.clients.domain.ApiSession;
import com.iaf.console.bff.clients.domain.BrowserOnlyException;
import com.iaf.console.bff.clients.domain.DeviceGrant;
import com.iaf.console.bff.clients.domain.DeviceGrantDecidedException;
import com.iaf.console.bff.clients.domain.UserCode;
import com.iaf.console.bff.clients.ports.outbound.DeviceGrantStore;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.bff.session.domain.StepUpRequiredException;
import com.iaf.console.kit.errors.NotFoundException;

import reactor.core.publisher.Mono;

/** The person's side of the device grant: reading a grant by its code, and approving it (with a fresh sign-in) or denying it once. */
class DeviceAuthorizationTest {

    private final Fixtures f = new Fixtures();

    @Test
    void approvingNeedsASignInAsFreshAsADeleteDoes() {
        DeviceAuthorization.Started started = f.authorization.start("console-cli", CLI).block();
        Session longAgo = f.browser("gonzalo", NOW.minus(Duration.ofMinutes(11)));
        StepUpRequiredException stale = assertThrows(StepUpRequiredException.class, () -> f.authorization.approve(longAgo, started.userCode()).block());
        assertEquals(DeviceAuthorization.APPROVE_ACTION, stale.action());
        assertEquals("pending", f.authorization.statusOf(f.authorization.find(started.userCode()).block()));

        Session fresh = f.browser("gonzalo", NOW.minus(Duration.ofMinutes(9)));
        DeviceGrant approved = f.authorization.approve(fresh, started.userCode()).block();
        assertEquals("approved", f.authorization.statusOf(approved));
        assertEquals(NOW, approved.decidedAt());
        assertEquals("user_gonzalo", approved.approver().user().userId());
    }

    @Test
    void denyingNeedsNoFreshSignInAndAGrantIsDecidedOnce() {
        DeviceAuthorization.Started started = f.authorization.start("console-cli", CLI).block();
        Session longAgo = f.browser("mia", NOW.minus(Duration.ofHours(2)));
        assertEquals("denied", f.authorization.statusOf(f.authorization.deny(longAgo, started.userCode()).block()));
        DeviceGrantDecidedException again = assertThrows(DeviceGrantDecidedException.class,
                () -> f.authorization.approve(f.browser("gonzalo", NOW), started.userCode()).block());
        assertEquals("denied", again.detail().get("status"));
        assertThrows(DeviceGrantDecidedException.class, () -> f.authorization.deny(longAgo, started.userCode()).block());
    }

    @Test
    void aCodeIsFoundWhateverItsCaseOrHyphenAndAnythingElseIsA404() {
        DeviceAuthorization.Started started = f.authorization.start("console-sdk", CLI).block();
        String bare = started.userCode().replace("-", "");
        assertEquals(started.userCode(), UserCode.display(f.authorization.find(bare.toLowerCase()).block().userCode()));
        assertEquals("console-sdk", f.authorization.find(started.userCode()).block().clientId());
        assertThrows(NotFoundException.class, () -> f.authorization.find("BCDF-GHJ").block());
        assertThrows(NotFoundException.class, () -> f.authorization.find(null).block());
        String other = bare.charAt(0) == 'B' ? "C" + bare.substring(1) : "B" + bare.substring(1);
        assertThrows(NotFoundException.class, () -> f.authorization.find(other).block());
        assertThrows(NotFoundException.class, () -> f.authorization.approve(f.browser("gonzalo", NOW), other).block());
    }

    @Test
    void aBearerSessionNeverDecidesAGrant() {
        DeviceAuthorization.Started started = f.authorization.start("console-cli", CLI).block();
        ApiTokens.Issued issued = f.signedIn("gonzalo", "console-cli");
        Session bearer = f.apiSessions.authenticate(issued.accessToken(), null, null).block();
        assertTrue(ApiSession.isId(bearer.id()));
        assertThrows(BrowserOnlyException.class, () -> f.authorization.approve(bearer, started.userCode()).block());
        assertThrows(BrowserOnlyException.class, () -> f.authorization.deny(bearer, started.userCode()).block());
    }

    @Test
    void aDecisionThatRacesAPollIsMadeOnTheGrantAsThePollLeftIt() {
        AtomicBoolean pollFirst = new AtomicBoolean(true);
        DeviceGrantStore racing = new DeviceGrantStore() {
            @Override
            public Mono<Boolean> create(DeviceGrant grant) {
                return f.grants.create(grant);
            }

            @Override
            public Mono<DeviceGrant> findByUserCode(String userCode, Instant now) {
                return f.grants.findByUserCode(userCode, now);
            }

            @Override
            public Mono<DeviceGrant> findByDeviceCode(String deviceCodeHash, Instant now) {
                return f.grants.findByDeviceCode(deviceCodeHash, now);
            }

            @Override
            public Mono<Long> countRequestedFrom(String ip, Instant since) {
                return f.grants.countRequestedFrom(ip, since);
            }

            @Override
            public Mono<DeviceGrant> update(DeviceGrant grant, long expectedVersion) {
                if (grant.status() == DeviceGrant.Status.APPROVED && pollFirst.getAndSet(false)) {
                    // the CLI's poll lands between the approval's read and its write
                    DeviceGrant current = f.grants.findByUserCode(grant.userCode(), NOW).block();
                    f.grants.update(current.poll(NOW, "aps_unused").next(), current.version()).block();
                }
                return f.grants.update(grant, expectedVersion);
            }
        };
        DeviceAuthorization authorization = new DeviceAuthorization(racing, f.sessions, f.properties, f.clock);
        DeviceAuthorization.Started started = authorization.start("console-cli", CLI).block();
        DeviceGrant approved = authorization.approve(f.browser("gonzalo", NOW), started.userCode()).block();
        assertEquals(DeviceGrant.Status.APPROVED, approved.status());
        assertEquals(NOW, approved.lastPolledAt(), "the poll's change is kept");
        assertEquals(3, approved.version());
    }

    @Test
    void theCompletePageAddsTheCodeToWhateverQueryThePageHas() {
        ClientsProperties withQuery = new ClientsProperties("https://console.example.com/#/device?from=cli", Duration.ofMinutes(10), Duration.ofSeconds(5),
                10, Duration.ofMinutes(15), Duration.ofHours(12), Duration.ofHours(8), Duration.ofMinutes(1), 0);
        DeviceAuthorization.Started started = new DeviceAuthorization(f.grants, f.sessions, withQuery, f.clock).start("console-cli", CLI).block();
        assertEquals("https://console.example.com/#/device?from=cli&code=" + started.userCode(), started.verificationUriComplete());
        assertThrows(IllegalArgumentException.class, () -> new ClientsProperties(" ", Duration.ofMinutes(10), Duration.ofSeconds(5), 10,
                Duration.ofMinutes(15), Duration.ofHours(12), Duration.ofHours(8), Duration.ofMinutes(1), 0));
        assertThrows(IllegalArgumentException.class, () -> new ClientsProperties("http://x/#/device", Duration.ZERO, Duration.ofSeconds(5), 10,
                Duration.ofMinutes(15), Duration.ofHours(12), Duration.ofHours(8), Duration.ofMinutes(1), 0));
    }
}
