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
 * and a Reactor context entry, so a controller or a service deep in a chain can read it.
 */
public final class CurrentSession {

    public static final String ATTRIBUTE = CurrentSession.class.getName() + ".session";
    public static final String CONTEXT_KEY = "console.session";

    private CurrentSession() {
    }

    public static Optional<Session> of(ServerWebExchange exchange) {
        Object value = exchange.getAttribute(ATTRIBUTE);
        return value instanceof Session s ? Optional.of(s) : Optional.empty();
    }

    public static Optional<Session> of(ContextView ctx) {
        return ctx.getOrEmpty(CONTEXT_KEY);
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
