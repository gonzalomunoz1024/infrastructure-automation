package com.iaf.console.bff.session.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

import com.iaf.console.bff.session.domain.AccountMembership;
import com.iaf.console.bff.session.domain.CsrfTokens;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.bff.session.domain.StepUpPolicy;
import com.iaf.console.bff.session.domain.StepUpRequiredException;
import com.iaf.console.bff.session.ports.outbound.RegionsPort;
import com.iaf.console.bff.session.ports.outbound.SessionStore;
import com.iaf.console.kit.errors.NotFoundException;

import reactor.core.publisher.Mono;

/**
 * Sessions after sign-in: finding the one a cookie names (and ending it when expired or idle),
 * the view the web app reads, switching account or region, the CSRF token, the step-up check
 * and logout.
 */
public class Sessions {

    private static final Pattern REGION = Pattern.compile("^[a-z0-9-]{2,32}$");
    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    private final SessionStore store;
    private final CsrfTokens csrf;
    private final StepUpPolicy stepUp;
    private final RegionsPort regions;
    private final SessionProperties properties;
    private final Clock clock;

    public Sessions(SessionStore store, CsrfTokens csrf, StepUpPolicy stepUp, RegionsPort regions, SessionProperties properties, Clock clock) {
        this.store = store;
        this.csrf = csrf;
        this.stepUp = stepUp;
        this.regions = regions;
        this.properties = properties;
        this.clock = clock;
    }

    /** The live session a cookie names; empty when there is none, it expired, or it went idle. */
    public Mono<Session> find(String cookieValue) {
        if (cookieValue == null || cookieValue.isBlank()) {
            return Mono.empty();
        }
        Instant now = clock.instant();
        return store.find(cookieValue).flatMap(session -> {
            if (session.expired(now) || session.lastSeenAt().plus(properties.idleTimeout()).isBefore(now)) {
                return store.delete(session.id()).then(Mono.empty());
            }
            if (session.lastSeenAt().plus(TOUCH_INTERVAL).isBefore(now)) {
                Session seen = session.seenAt(now);
                return store.save(seen).thenReturn(seen);
            }
            return Mono.just(session);
        });
    }

    public SessionView view(Session session) {
        AccountMembership account = session.currentAccount();
        return new SessionView(
                new SessionView.User(session.user().userId(), session.user().email(), session.user().name(), session.user().groups()),
                new SessionView.Account(account.accountId(), account.name(), account.status(), account.defaultRegion(), account.roles(), account.groups()),
                session.region(), csrf.tokenFor(session.id()), session.expiresAt(), session.authTime(),
                new SessionView.StepUp(stepUp.actions(), session.authTime().plus(stepUp.maxAge())), null);
    }

    public String csrfToken(Session session) {
        return csrf.tokenFor(session.id());
    }

    public boolean csrfMatches(Session session, String presented) {
        return csrf.matches(session.id(), presented);
    }

    /** Switches account and/or region; an account the user is not in answers 404, an unknown region 400. */
    public Mono<Session> switchTo(Session session, String accountId, String region) {
        return switched(session, accountId, region).flatMap(switched -> store.save(switched).thenReturn(switched));
    }

    /**
     * The session in another account and/or region, checked as a switch is (an account the user is
     * not in is a 404, an unknown region a 400) but not saved: a bearer call names its own per
     * call (decision 31).
     */
    public Mono<Session> switched(Session session, String accountId, String region) {
        return Mono.defer(() -> switchedNow(session, accountId, region));
    }

    private Mono<Session> switchedNow(Session session, String accountId, String region) {
        String nextAccount = accountId == null || accountId.isBlank() ? session.accountId() : accountId.trim();
        AccountMembership membership = session.membership(nextAccount).orElseThrow(() -> NotFoundException.of("account", nextAccount));
        String nextRegion;
        if (region == null || region.isBlank()) {
            nextRegion = nextAccount.equals(session.accountId()) ? session.region()
                    : (membership.defaultRegion() == null || membership.defaultRegion().isBlank() ? session.region() : membership.defaultRegion());
        } else {
            nextRegion = region.trim();
        }
        if (!REGION.matcher(nextRegion).matches()) {
            return Mono.error(new IllegalArgumentException("a region is 2 to 32 lowercase letters, digits or dashes"));
        }
        final String chosenRegion = nextRegion;
        return regions.listRegionNames().flatMap(names -> {
            if (!names.contains(chosenRegion)) {
                return Mono.error(new IllegalArgumentException("unknown region " + chosenRegion));
            }
            return Mono.just(session.switchedTo(nextAccount, chosenRegion));
        });
    }

    public Mono<Session> refreshAccounts(Session session, List<AccountMembership> accounts) {
        if (accounts.isEmpty() || session.membership(session.accountId()).isEmpty()) {
            return Mono.just(session);
        }
        Session refreshed = session.withAccounts(accounts);
        return store.save(refreshed).thenReturn(refreshed);
    }

    /** Errors with {@link StepUpRequiredException} when the action is guarded and the session's authentication is too old. */
    public Mono<Void> requireFreshFor(Session session, String action) {
        return stepUp.requiresStepUp(session, action, clock.instant()) ? Mono.error(new StepUpRequiredException(action)) : Mono.empty();
    }

    public StepUpPolicy stepUpPolicy() {
        return stepUp;
    }

    public Mono<Void> logout(Session session) {
        return store.delete(session.id());
    }
}
