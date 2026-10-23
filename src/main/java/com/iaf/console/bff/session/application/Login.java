package com.iaf.console.bff.session.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.bff.session.domain.AccountMembership;
import com.iaf.console.bff.session.domain.IdpUser;
import com.iaf.console.bff.session.domain.LoginTicket;
import com.iaf.console.bff.session.domain.LoginTicketCodec;
import com.iaf.console.bff.session.domain.Pkce;
import com.iaf.console.bff.session.domain.ReturnTo;
import com.iaf.console.bff.session.domain.ResolvedPrincipal;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.bff.session.ports.outbound.IdentityProviderPort;
import com.iaf.console.bff.session.ports.outbound.PrincipalResolverPort;
import com.iaf.console.bff.session.ports.outbound.SessionStore;

import reactor.core.publisher.Mono;

/**
 * The sign-in: {@link #start} sends the browser to the provider with PKCE and seals what the
 * callback needs into a ticket; {@link #complete} exchanges the code, verifies the ID token,
 * resolves the console user and opens the session (or, for a step-up, refreshes the current
 * session's authentication time).
 */
public class Login {

    private static final Logger log = LoggerFactory.getLogger(Login.class);

    private final IdentityProviderPort provider;
    private final PrincipalResolverPort resolver;
    private final SessionStore sessions;
    private final LoginTicketCodec tickets;
    private final SessionProperties properties;
    private final Clock clock;

    public Login(IdentityProviderPort provider, PrincipalResolverPort resolver, SessionStore sessions, LoginTicketCodec tickets,
            SessionProperties properties, Clock clock) {
        this.provider = provider;
        this.resolver = resolver;
        this.sessions = sessions;
        this.tickets = tickets;
        this.properties = properties;
        this.clock = clock;
    }

    /** Where the browser goes and the sealed ticket to set as a cookie. */
    public record Started(String redirectTo, String ticketCookie) {
    }

    /** The outcome of a callback: the session (new, or the step-up's), where to send the browser, and whether a new cookie is needed. */
    public record Completed(Session session, String returnTo, boolean newSession) {
    }

    public Mono<Started> start(String returnTo, String loginHint, boolean stepUp, Optional<Session> current) {
        String verifier = Pkce.verifier();
        LoginTicket ticket = new LoginTicket(Pkce.random(24), verifier, Pkce.random(24), ReturnTo.sanitize(returnTo),
                stepUp ? current.map(Session::id).orElse(null) : null, stepUp && current.isPresent(), clock.instant().plus(properties.ticketTtl()));
        IdentityProviderPort.AuthorizationRequest request = new IdentityProviderPort.AuthorizationRequest(ticket.state(), ticket.nonce(),
                Pkce.challenge(verifier), blankToNull(loginHint), ticket.stepUp());
        return provider.authorizationUrl(request).map(url -> new Started(url, tickets.seal(ticket)));
    }

    public Mono<Completed> complete(String code, String state, String ticketCookie) {
        Instant now = clock.instant();
        Optional<LoginTicket> opened = tickets.open(ticketCookie, now);
        if (opened.isEmpty()) {
            return Mono.error(new IllegalArgumentException("the sign-in expired or was not started here; start again"));
        }
        LoginTicket ticket = opened.get();
        if (state == null || !state.equals(ticket.state())) {
            return Mono.error(new IllegalArgumentException("the sign-in's state does not match"));
        }
        if (code == null || code.isBlank()) {
            return Mono.error(new IllegalArgumentException("the identity provider sent no code"));
        }
        return provider.exchange(code, ticket.codeVerifier(), ticket.nonce())
                .flatMap(user -> ticket.stepUp() ? stepUp(ticket, user, now) : open(ticket, user, now));
    }

    private Mono<Completed> open(LoginTicket ticket, IdpUser user, Instant now) {
        return resolver.resolve(user).flatMap(resolved -> {
            Session session = newSession(user, resolved, now);
            log.info("sign-in user={} account={} issuer={}", session.user().userId(), session.accountId(), user.issuer());
            return sessions.save(session).thenReturn(new Completed(session, ticket.returnTo(), true));
        });
    }

    private Mono<Completed> stepUp(LoginTicket ticket, IdpUser user, Instant now) {
        return sessions.find(ticket.sessionId())
                .filter(session -> !session.expired(now))
                .switchIfEmpty(Mono.defer(() -> open(ticket, user, now).map(Completed::session)))
                .flatMap(session -> {
                    if (!session.issuer().equals(user.issuer()) || !session.subject().equals(user.subject())) {
                        return Mono.error(new IllegalArgumentException("the step-up was completed by another user"));
                    }
                    Session refreshed = session.reauthenticatedAt(user.authTime() == null ? now : user.authTime());
                    log.info("step-up user={} account={}", refreshed.user().userId(), refreshed.accountId());
                    return sessions.save(refreshed).thenReturn(new Completed(refreshed, ticket.returnTo(), false));
                });
    }

    private Session newSession(IdpUser user, ResolvedPrincipal resolved, Instant now) {
        AccountMembership first = resolved.accounts().get(0);
        String region = first.defaultRegion() == null || first.defaultRegion().isBlank() ? "global" : first.defaultRegion();
        return new Session(Pkce.random(32), resolved.user(), List.copyOf(resolved.accounts()), first.accountId(), region, now,
                user.authTime() == null ? now : user.authTime(), now, now.plus(properties.ttl()), user.issuer(), user.subject());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
