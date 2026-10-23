package com.iaf.console.bff.session.adapters.inbound;

import java.util.Optional;

import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.bff.common.web.JsonResponses;
import com.iaf.console.bff.session.application.CurrentSession;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.bff.session.application.Sessions;
import com.iaf.console.kit.errors.Errors;

import reactor.core.publisher.Mono;

/**
 * Finds the session the cookie names and attaches it to the request; a {@code /console/v1} call
 * without one is answered 401 here, before any controller runs. A bearer call, whose session the
 * bearer filter attached already, reads no cookie.
 */
@Component
public class SessionWebFilter implements WebFilter, Ordered {

    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 20;
    static final String CONSOLE_PREFIX = "/console/";

    private final Sessions sessions;
    private final SessionCookies cookies;
    private final ObjectMapper json;

    public SessionWebFilter(Sessions sessions, SessionCookies cookies, ObjectMapper json) {
        this.sessions = sessions;
        this.cookies = cookies;
        this.json = json;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (CurrentSession.bearer(exchange)) {
            return chain.filter(exchange);
        }
        String path = exchange.getRequest().getPath().value();
        boolean console = path.startsWith(CONSOLE_PREFIX);
        String cookie = cookies.sessionValue(exchange);
        // chain.filter completes empty, so the "no session" branch must not hang off switchIfEmpty
        Mono<Optional<Session>> found = cookie == null ? Mono.just(Optional.empty())
                : sessions.find(cookie).map(Optional::of).defaultIfEmpty(Optional.empty());
        return found.flatMap(session -> {
            if (session.isPresent()) {
                exchange.getAttributes().put(CurrentSession.ATTRIBUTE, session.get());
                return chain.filter(exchange).contextWrite(ctx -> ctx.put(CurrentSession.CONTEXT_KEY, session.get()));
            }
            if (console) {
                if (cookie != null) {
                    exchange.getResponse().addCookie(cookies.clearSession());
                }
                return JsonResponses.write(exchange.getResponse(), HttpStatus.UNAUTHORIZED, json, Errors.of("no session"));
            }
            return chain.filter(exchange);
        });
    }
}
