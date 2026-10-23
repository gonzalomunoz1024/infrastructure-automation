package com.iaf.console.bff.session.adapters.inbound;

import java.net.URI;
import java.util.Set;

import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.bff.common.web.JsonResponses;
import com.iaf.console.bff.common.web.WebProperties;
import com.iaf.console.bff.session.application.CurrentSession;
import com.iaf.console.bff.session.application.Sessions;
import com.iaf.console.kit.errors.Errors;

import reactor.core.publisher.Mono;

/**
 * Every state-changing call under {@code /console/v1} and the logout must carry the session's
 * CSRF token in {@code X-Csrf-Token}; when the browser sends an {@code Origin}, it must be the
 * console's own origin or one of the web app's. A failure is 403 with no {@code denied} block,
 * since no policy decided it.
 */
@Component
public class CsrfWebFilter implements WebFilter, Ordered {

    public static final String HEADER = "X-Csrf-Token";
    public static final String ERROR = "csrf token missing or invalid";
    public static final int ORDER = SessionWebFilter.ORDER + 10;

    private static final Set<HttpMethod> UNSAFE = Set.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE);

    private final Sessions sessions;
    private final WebProperties web;
    private final ObjectMapper json;

    public CsrfWebFilter(Sessions sessions, WebProperties web, ObjectMapper json) {
        this.sessions = sessions;
        this.web = web;
        this.json = json;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        HttpMethod method = exchange.getRequest().getMethod();
        boolean guarded = UNSAFE.contains(method) && (path.startsWith(SessionWebFilter.CONSOLE_PREFIX) || path.equals("/auth/logout"));
        if (!guarded) {
            return chain.filter(exchange);
        }
        return CurrentSession.of(exchange).map(session -> {
            String presented = exchange.getRequest().getHeaders().getFirst(HEADER);
            if (!sessions.csrfMatches(session, presented) || !originAllowed(exchange)) {
                return JsonResponses.write(exchange.getResponse(), HttpStatus.FORBIDDEN, json, Errors.of(ERROR));
            }
            return chain.filter(exchange);
        }).orElseGet(() -> chain.filter(exchange));
    }

    private boolean originAllowed(ServerWebExchange exchange) {
        String origin = exchange.getRequest().getHeaders().getOrigin();
        if (origin == null || origin.isBlank() || "null".equals(origin)) {
            return true;
        }
        if (web.origins().contains(origin)) {
            return true;
        }
        URI self = exchange.getRequest().getURI();
        String own = self.getScheme() + "://" + self.getAuthority();
        if (own.equalsIgnoreCase(origin)) {
            return true;
        }
        String forwardedHost = exchange.getRequest().getHeaders().getFirst("X-Forwarded-Host");
        String forwardedProto = exchange.getRequest().getHeaders().getFirst("X-Forwarded-Proto");
        if (forwardedHost != null) {
            String forwarded = (forwardedProto == null ? self.getScheme() : forwardedProto) + "://" + forwardedHost;
            return forwarded.equalsIgnoreCase(origin);
        }
        return false;
    }
}
