package com.iaf.console.bff.session.application;

import java.util.Optional;

import org.springframework.web.server.ServerWebExchange;

import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.kit.errors.ForbiddenException;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

/**
 * Where the session of a request is kept once the session filter found it: an exchange attribute
 * and a Reactor context entry, so a controller or a service deep in a chain can read it. A CLI or
 * SDK's bearer call (decision 31) carries a session too, built from its API session, and is marked
 * so: it read no cookie and needs no CSRF token.
 */
public final class CurrentSession {

    public static final String ATTRIBUTE = CurrentSession.class.getName() + ".session";
    public static final String CONTEXT_KEY = "console.session";
    /** Set to {@code true} beside the session when a bearer token, not the cookie, made it. */
    public static final String BEARER_ATTRIBUTE = CurrentSession.class.getName() + ".bearer";

    private CurrentSession() {
    }

    public static Optional<Session> of(ServerWebExchange exchange) {
        Object value = exchange.getAttribute(ATTRIBUTE);
        return value instanceof Session s ? Optional.of(s) : Optional.empty();
    }

    public static Optional<Session> of(ContextView ctx) {
        return ctx.getOrEmpty(CONTEXT_KEY);
    }

    /** Whether the request's session came from a bearer token rather than the session cookie. */
    public static boolean bearer(ServerWebExchange exchange) {
        return Boolean.TRUE.equals(exchange.getAttribute(BEARER_ATTRIBUTE));
    }

    /** The session of the request, or a 401-mapped error. */
    public static Mono<Session> required(ServerWebExchange exchange) {
        return of(exchange).map(Mono::just).orElseGet(() -> Mono.error(new NoSessionException()));
    }

    /** The account scope of a session: its account, region and user. */
    public static AccountScope scope(Session session) {
        return AccountScope.of(session.accountId(), session.region(), session.user().userId());
    }

    /** A request without a session, which the error handler answers 401. */
    public static final class NoSessionException extends ForbiddenException {

        private static final long serialVersionUID = 1L;

        public NoSessionException() {
            super("no session");
        }
    }
}
