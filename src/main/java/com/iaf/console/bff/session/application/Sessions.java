package com.iaf.console.bff.session.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.bff.session.domain.AccountMembership;
import com.iaf.console.bff.session.domain.CsrfTokens;
import com.iaf.console.bff.session.domain.IdpUser;
import com.iaf.console.bff.session.domain.NoConsoleAccountException;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.bff.session.domain.StepUpPolicy;
import com.iaf.console.bff.session.domain.StepUpRequiredException;
import com.iaf.console.bff.session.ports.outbound.PrincipalResolverPort;
import com.iaf.console.bff.session.ports.outbound.RegionsPort;
import com.iaf.console.bff.session.ports.outbound.SessionStore;
import com.iaf.console.kit.errors.NotFoundException;

import reactor.core.publisher.Mono;

/**
 * Sessions after sign-in: finding the one a cookie names (and ending it when expired or idle),
 * reading its memberships again when they are due (the re-read interval, the end of a time-boxed
 * role, or an identity change heard since), the view the web app reads, switching account or
 * region, the CSRF token, the step-up check and logout. A re-read that finds the person no longer
 * in the session's account, or in no account, ends the session.
 */
public class Sessions {

    private static final Logger log = LoggerFactory.getLogger(Sessions.class);

    private static final Pattern REGION = Pattern.compile("^[a-z0-9-]{2,32}$");
    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    private final SessionStore store;
    private final CsrfTokens csrf;
    private final StepUpPolicy stepUp;
    private final RegionsPort regions;
    private final PrincipalResolverPort resolver;
    private final MembershipChanges changes;
    private final SessionProperties properties;
    private final Clock clock;

    public Sessions(SessionStore store, CsrfTokens csrf, StepUpPolicy stepUp, RegionsPort regions, PrincipalResolverPort resolver,
            MembershipChanges changes, SessionProperties properties, Clock clock) {
        this.store = store;
        this.csrf = csrf;
        this.stepUp = stepUp;
        this.regions = regions;
        this.resolver = resolver;
        this.changes = changes;
        this.properties = properties;
        this.clock = clock;
    }

    /** Until when memberships read at {@code now} may be used: the re-read interval, and never past the earliest time-boxed role's end. */
    public static Instant rolesUntil(Instant now, List<AccountMembership> accounts, Duration interval) {
        Instant until = now.plus(interval);
        Instant elevation = Session.earliestElevationEnd(accounts);
        return elevation != null && elevation.isBefore(until) ? elevation : until;
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
            if (session.rolesDue(now) || changes.changedSince(session)) {
                return refreshAccounts(session.seenAt(now), now);
            }
            if (session.lastSeenAt().plus(TOUCH_INTERVAL).isBefore(now)) {
                Session seen = session.seenAt(now);
                return updated(seen);
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
                new SessionView.StepUp(stepUp.actions(), session.authTime().plus(stepUp.maxAge())), session.organizationAdmin(), null);
    }

    public String csrfToken(Session session) {
        return csrf.tokenFor(session.id());
    }

    public boolean csrfMatches(Session session, String presented) {
        return csrf.matches(session.id(), presented);
    }

    /** Switches account and/or region; an account the user is not in answers 404, an unknown region 400. */
    public Mono<Session> switchTo(Session session, String accountId, String region) {
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
            Session switched = session.switchedTo(nextAccount, chosenRegion);
            return updated(switched).switchIfEmpty(Mono.error(CurrentSession.NoSessionException::new));
        });
    }

    /**
     * Reads the person's memberships again ({@code principals:resolve}, with the identity
     * provider's groups of the sign-in) and stores them; empty, with the session ended, when the
     * person is in no account any more or not in the session's account. Any other failure errors,
     * so the request is refused rather than served with roles that may have ended.
     */
    Mono<Session> refreshAccounts(Session session, Instant now) {
        if (session.idpGroups() == null || session.issuer() == null || session.subject() == null) {
            log.info("session of user={} predates membership re-reads; ending it", session.user().userId());
            return store.delete(session.id()).then(Mono.empty());
        }
        IdpUser person = new IdpUser(session.issuer(), session.subject(), session.user().email(), session.user().name(), session.idpGroups(),
                session.authTime());
        return resolver.resolve(person)
                .flatMap(resolved -> {
                    if (!resolved.user().userId().equals(session.user().userId())
                            || resolved.accounts().stream().noneMatch(a -> a.accountId().equals(session.accountId()))) {
                        log.info("user={} is no longer in account={}; ending the session", session.user().userId(), session.accountId());
                        return store.delete(session.id()).then(Mono.<Session>empty());
                    }
                    Session refreshed = session.withAccounts(resolved.user(), resolved.accounts(), now,
                            rolesUntil(now, resolved.accounts(), properties.rolesRefreshInterval()));
                    return updated(refreshed);
                })
                .onErrorResume(NoConsoleAccountException.class, e -> {
                    log.info("user={} belongs to no account any more; ending the session", session.user().userId());
                    return store.delete(session.id()).then(Mono.empty());
                });
    }

    /** The session as written, or empty when it ended meanwhile (a logout): no write brings a session back. */
    private Mono<Session> updated(Session session) {
        return store.update(session).flatMap(written -> written ? Mono.just(session) : Mono.empty());
    }

    /** Errors with {@link StepUpRequiredException} when the action is guarded and the session's authentication is too old. */
    public Mono<Void> requireFreshFor(Session session, String action) {
        return stepUp.requiresStepUp(session, action, clock.instant()) ? Mono.error(new StepUpRequiredException(action)) : Mono.empty();
    }

    /**
     * Errors with {@link StepUpRequiredException} when the session's authentication is too old,
     * whatever the step-up patterns say: for writes that always need a fresh sign-in (the
     * organization's).
     */
    public Mono<Void> requireFresh(Session session, String action) {
        return stepUp.fresh(session, clock.instant()) ? Mono.empty() : Mono.error(new StepUpRequiredException(action));
    }

    public StepUpPolicy stepUpPolicy() {
        return stepUp;
    }

    public Mono<Void> logout(Session session) {
        return store.delete(session.id());
    }
}
