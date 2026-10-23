package com.iaf.console.bff.clients.adapters.inbound;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.bff.clients.application.ApiSessions;
import com.iaf.console.bff.clients.domain.BrowserOnlyException;
import com.iaf.console.bff.clients.domain.InvalidBearerTokenException;
import com.iaf.console.bff.clients.domain.NotMemberException;
import com.iaf.console.bff.common.web.BffExceptionHandler;
import com.iaf.console.bff.common.web.JsonResponses;
import com.iaf.console.bff.session.application.CurrentSession;
import com.iaf.console.bff.session.application.SessionProperties;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.kit.correlation.Correlation;
import com.iaf.console.kit.errors.Errors;
import com.iaf.console.kit.errors.NotFoundException;

import reactor.core.publisher.Mono;

/**
 * A CLI or SDK's call (decision 31): {@code Authorization: Bearer} with a console API access
 * token on {@code /console/v1/**} makes the request's session from the API session the token
 * names, in the account and region {@code X-Console-Account} and {@code X-Console-Region} name,
 * before the cookie's filter runs; such a call reads no cookie and needs no CSRF token. A call
 * with both a bearer token and the session cookie is a 400 {@code auth.ambiguous}; a token the
 * API does not accept (expired, of an ended API session, or not its access token at all, such as
 * an internal token) a 401 with {@code WWW-Authenticate: Bearer error="invalid_token"}; an
 * account the person is not a member of a 403 {@code account.not-member}. Signing in, switching
 * the browser session and deciding device grants are browser-only: a 403 {@code browser.only}.
 * Paths are matched as the handlers match them, decoded. Any other path, such as the stand-ins'
 * internal calls, is left as it is.
 */
@Component
public class BearerWebFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(BearerWebFilter.class);

    /** Ahead of the session filter, which reads the cookie. */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 15;
    public static final String ACCOUNT_HEADER = "X-Console-Account";
    public static final String REGION_HEADER = "X-Console-Region";
    public static final String AMBIGUOUS = "auth.ambiguous";

    private static final PathPattern CONSOLE_API = PathPatternParser.defaultInstance.parse("/console/v1/**");

    /** A route a bearer token may not call: its method (any when null) and its path. */
    private record Route(HttpMethod method, PathPattern path) {

        boolean matches(HttpMethod requested, PathContainer requestedPath) {
            return (method == null || method.equals(requested)) && path.matches(requestedPath);
        }
    }

    private static final List<Route> BROWSER_ONLY = List.of(
            new Route(null, PathPatternParser.defaultInstance.parse("/auth/login")),
            new Route(HttpMethod.PUT, PathPatternParser.defaultInstance.parse("/console/v1/session")),
            new Route(HttpMethod.POST, PathPatternParser.defaultInstance.parse("/console/v1/device-grants/{userCode}:approve")),
            new Route(HttpMethod.POST, PathPatternParser.defaultInstance.parse("/console/v1/device-grants/{userCode}:deny")));

    private final ApiSessions apiSessions;
    private final SessionProperties session;
    private final ObjectMapper json;

    public BearerWebFilter(ApiSessions apiSessions, SessionProperties session, ObjectMapper json) {
        this.apiSessions = apiSessions;
        this.session = session;
        this.json = json;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String token = bearerToken(request);
        if (token == null) {
            return chain.filter(exchange);
        }
        PathContainer path = request.getPath().pathWithinApplication();
        if (BROWSER_ONLY.stream().anyMatch(route -> route.matches(request.getMethod(), path))) {
            BrowserOnlyException refused = new BrowserOnlyException();
            return write(exchange, HttpStatus.FORBIDDEN, coded(exchange, BrowserOnlyException.CODE, refused.getMessage(), null));
        }
        if (!CONSOLE_API.matches(path)) {
            return chain.filter(exchange);
        }
        if (request.getCookies().containsKey(session.cookieName())) {
            return write(exchange, HttpStatus.BAD_REQUEST,
                    coded(exchange, AMBIGUOUS, "send either the bearer token or the session cookie, not both", null));
        }
        HttpHeaders headers = request.getHeaders();
        return apiSessions.authenticate(token, headers.getFirst(ACCOUNT_HEADER), headers.getFirst(REGION_HEADER))
                .map(Authenticated::of)
                .onErrorResume(e -> Mono.just(Authenticated.refused(e)))
                .defaultIfEmpty(Authenticated.refused(new InvalidBearerTokenException("no session was made")))
                .flatMap(result -> result.session() != null ? proceed(exchange, chain, result.session()) : refuse(exchange, result.error()));
    }

    /** The outcome of the authentication, kept apart from the chain's own errors. */
    private record Authenticated(Session session, Throwable error) {

        static Authenticated of(Session session) {
            return new Authenticated(session, null);
        }

        static Authenticated refused(Throwable error) {
            return new Authenticated(null, error);
        }
    }

    private static Mono<Void> proceed(ServerWebExchange exchange, WebFilterChain chain, Session session) {
        exchange.getAttributes().put(CurrentSession.ATTRIBUTE, session);
        exchange.getAttributes().put(CurrentSession.BEARER_ATTRIBUTE, Boolean.TRUE);
        return chain.filter(exchange).contextWrite(ctx -> ctx.put(CurrentSession.CONTEXT_KEY, session));
    }

    private Mono<Void> refuse(ServerWebExchange exchange, Throwable error) {
        if (error instanceof InvalidBearerTokenException invalid) {
            log.info("bearer call refused: {} [{}]", invalid.reason(), Correlation.of(exchange));
            exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"" + InvalidBearerTokenException.CODE + "\"");
            return write(exchange, HttpStatus.UNAUTHORIZED, coded(exchange, InvalidBearerTokenException.CODE, invalid.getMessage(), null));
        }
        if (error instanceof NotMemberException notMember) {
            return write(exchange, HttpStatus.FORBIDDEN,
                    coded(exchange, NotMemberException.CODE, notMember.getMessage(), Map.of("accountId", notMember.accountId())));
        }
        if (error instanceof NotFoundException notFound) {
            return write(exchange, HttpStatus.NOT_FOUND, Errors.of(notFound.getMessage()));
        }
        if (error instanceof IllegalArgumentException invalid) {
            return write(exchange, HttpStatus.BAD_REQUEST, Errors.of(invalid.getMessage()));
        }
        return Mono.error(error);
    }

    private Mono<Void> write(ServerWebExchange exchange, HttpStatus status, Object body) {
        return JsonResponses.write(exchange.getResponse(), status, json, body);
    }

    private static BffExceptionHandler.CodedBody coded(ServerWebExchange exchange, String code, String message, Object detail) {
        return new BffExceptionHandler.CodedBody(code, message, null, Correlation.of(exchange), detail);
    }

    /** The token of an {@code Authorization: Bearer} header (the scheme in any case, the token maybe empty); null without one. */
    static String bearerToken(ServerHttpRequest request) {
        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null) {
            return null;
        }
        String value = authorization.strip();
        if (value.equalsIgnoreCase("Bearer")) {
            return "";
        }
        return value.regionMatches(true, 0, "Bearer ", 0, 7) ? value.substring(7).strip() : null;
    }
}
