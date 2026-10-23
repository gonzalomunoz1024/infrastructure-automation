package com.iaf.console.search.auth.adapters.web;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import com.iaf.console.search.auth.domain.ConsolePrincipal;
import com.iaf.console.search.auth.domain.InternalCallers;

import reactor.core.publisher.Mono;

/**
 * Authenticates every call but the actuator's: a bearer JWT the verifier accepts, or, in the demo
 * and dev profiles only, the dev headers {@code X-Console-Account}, {@code X-Console-User},
 * {@code X-Console-Service}, {@code X-Console-Region}, {@code X-Console-Groups} and
 * {@code X-Console-Roles} that stand in for the BFF. Anything else
 * is a 401. The principal rides on the exchange for the controllers.
 */
public final class ConsoleAuthWebFilter implements WebFilter, Ordered {

    public static final String ACCOUNT_HEADER = "X-Console-Account";
    public static final String USER_HEADER = "X-Console-User";
    public static final String SERVICE_HEADER = "X-Console-Service";
    public static final String REGION_HEADER = "X-Console-Region";
    public static final String GROUPS_HEADER = "X-Console-Groups";
    public static final String ROLES_HEADER = "X-Console-Roles";

    private static final Logger log = LoggerFactory.getLogger(ConsoleAuthWebFilter.class);

    private final JwtVerifier verifier;
    private final boolean devHeaders;
    private final InternalCallers callers;
    private final String defaultRegion;

    public ConsoleAuthWebFilter(JwtVerifier verifier, boolean devHeaders, InternalCallers callers, String defaultRegion) {
        this.verifier = verifier;
        this.callers = callers;
        this.devHeaders = devHeaders;
        this.defaultRegion = defaultRegion;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (path.startsWith("/actuator") || path.startsWith("/.well-known/")) {
            // the actuator does its own authentication
            return chain.filter(exchange);
        }
        HttpHeaders headers = exchange.getRequest().getHeaders();
        String authorization = headers.getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            // the verification's own failures only: an error further down the chain is the handler's
            return verifier.verify(authorization.substring(7).trim())
                    .map(Verified::of)
                    .onErrorResume(e -> Mono.just(Verified.failed(e)))
                    .flatMap(verified -> {
                        if (verified.principal() != null) {
                            exchange.getAttributes().put(ConsolePrincipal.ATTRIBUTE, verified.principal());
                            return chain.filter(exchange);
                        }
                        if (verified.error() instanceof JwtVerifier.InvalidToken invalid) {
                            log.info("rejected token: {}", invalid.getMessage());
                            return unauthorized(exchange, "unauthorized");
                        }
                        // the keys could not be read (the BFF's JWKS is down): the token may well be good, so not a 401
                        log.warn("the BFF's keys could not be read: {}", verified.error().toString());
                        return unavailable(exchange);
                    });
        }
        if (devHeaders) {
            String account = headers.getFirst(ACCOUNT_HEADER);
            String service = headers.getFirst(SERVICE_HEADER);
            if (account != null || service != null) {
                String user = headers.getFirst(USER_HEADER);
                String region = headers.getFirst(REGION_HEADER);
                List<String> groups = split(headers.getFirst(GROUPS_HEADER));
                List<String> roles = split(headers.getFirst(ROLES_HEADER));
                // the same callers as a token's: a service not on the list is refused here too
                ConsolePrincipal.Access access = callers.access(account, service);
                if (access == null) {
                    return unauthorized(exchange, "unauthorized");
                }
                ConsolePrincipal principal = new ConsolePrincipal(user != null ? user : service != null ? service : "dev",
                        access == ConsolePrincipal.Access.ACCOUNT ? account : null, region != null ? region : defaultRegion, groups, roles, service,
                        access);
                exchange.getAttributes().put(ConsolePrincipal.ATTRIBUTE, principal);
                return chain.filter(exchange);
            }
        }
        return unauthorized(exchange, "unauthorized");
    }

    /** The outcome of a verification: a principal, or why there is none. */
    private record Verified(ConsolePrincipal principal, Throwable error) {
        static Verified of(ConsolePrincipal principal) {
            return new Verified(principal, null);
        }

        static Verified failed(Throwable error) {
            return new Verified(null, error);
        }
    }

    private static Mono<Void> unavailable(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, "5");
        byte[] body = "{\"error\":\"the signing keys cannot be read now\"}".getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }

    private static List<String> split(String header) {
        return header == null || header.isBlank() ? List.of() : List.of(header.split(","));
    }

    private static Mono<Void> unauthorized(ServerWebExchange exchange, String error) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"console\"");
        byte[] body = ("{\"error\":\"" + error + "\"}").getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }
}
