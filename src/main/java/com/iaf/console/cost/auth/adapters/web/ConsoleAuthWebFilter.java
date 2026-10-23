package com.iaf.console.cost.auth.adapters.web;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import com.iaf.console.cost.auth.domain.ConsolePrincipal;
import com.iaf.console.cost.auth.domain.RouteGroup;
import com.iaf.console.cost.auth.domain.ServicePrincipals;

import reactor.core.publisher.Mono;

/**
 * Authenticates every call but the probes': a bearer JWT the verifier accepts, or, in the demo and
 * dev profiles only, the dev headers {@code X-Console-Account}, {@code X-Console-User},
 * {@code X-Console-Service}, {@code X-Console-Region}, {@code X-Console-Groups} and
 * {@code X-Console-Roles} that stand in for the BFF (a service named there must be one of the
 * {@link ServicePrincipals}, as a token's must). Anything else is a 401. The principal rides on the
 * exchange for the controllers, in this service's region when its token names none.
 *
 * <p>Without a token only {@code GET /actuator/health} (and its {@code liveness} and
 * {@code readiness} groups) and {@code /actuator/info} answer; every other actuator path needs one
 * like the API. On a separate management port ({@code management.server.port}, which the chart
 * sets and its Service does not route) the actuator is the whole server and answers the kubelet
 * and Prometheus without one.
 */
public final class ConsoleAuthWebFilter implements WebFilter, Ordered {

    public static final String ACCOUNT_HEADER = "X-Console-Account";
    public static final String USER_HEADER = "X-Console-User";
    public static final String SERVICE_HEADER = "X-Console-Service";
    public static final String REGION_HEADER = "X-Console-Region";
    public static final String GROUPS_HEADER = "X-Console-Groups";
    public static final String ROLES_HEADER = "X-Console-Roles";

    /** The actuator paths that answer without a token on the API's port: the probes and the build info. */
    static final Set<String> OPEN = Set.of("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness", "/actuator/info");

    /** The server namespace Spring Boot gives the actuator's own server on a separate management port. */
    private static final String MANAGEMENT = "management";

    private static final Logger log = LoggerFactory.getLogger(ConsoleAuthWebFilter.class);

    private final JwtVerifier verifier;
    private final ServicePrincipals services;
    private final boolean devHeaders;
    private final String defaultRegion;

    public ConsoleAuthWebFilter(JwtVerifier verifier, ServicePrincipals services, boolean devHeaders, String defaultRegion) {
        this.verifier = verifier;
        this.services = services;
        this.devHeaders = devHeaders;
        this.defaultRegion = defaultRegion;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (path.startsWith("/actuator/") && (onManagementServer(exchange) || OPEN.contains(path) && readOnly(exchange.getRequest().getMethod()))) {
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
                            exchange.getAttributes().put(ConsolePrincipal.ATTRIBUTE, verified.principal().orRegion(defaultRegion));
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
            ConsolePrincipal principal = fromDevHeaders(headers);
            if (principal != null) {
                exchange.getAttributes().put(ConsolePrincipal.ATTRIBUTE, principal);
                return chain.filter(exchange);
            }
        }
        return unauthorized(exchange, "unauthorized");
    }

    /** The demo's stand-in for the BFF's token: a person in an account, or a listed service as itself; null for anything else. */
    private ConsolePrincipal fromDevHeaders(HttpHeaders headers) {
        String account = headers.getFirst(ACCOUNT_HEADER);
        String service = headers.getFirst(SERVICE_HEADER);
        String region = headers.getFirst(REGION_HEADER);
        region = region != null ? region : defaultRegion;
        if (account != null && !account.isBlank()) {
            String user = headers.getFirst(USER_HEADER);
            return ConsolePrincipal.person(user != null ? user : service != null ? service : "dev", account, region, split(headers.getFirst(GROUPS_HEADER)),
                    split(headers.getFirst(ROLES_HEADER)), service);
        }
        if (service != null && !service.isBlank()) {
            Set<RouteGroup> routes = services.routesOf(service);
            if (routes.isEmpty()) {
                log.info("rejected dev header: the service '{}' may not call this service as itself", service);
                return null;
            }
            return ConsolePrincipal.service(service, region, routes);
        }
        return null;
    }

    /** Whether the request reached the actuator's own server on a separate management port. */
    private static boolean onManagementServer(ServerWebExchange exchange) {
        return WebServerApplicationContext.hasServerNamespace(exchange.getApplicationContext(), MANAGEMENT);
    }

    private static boolean readOnly(HttpMethod method) {
        return HttpMethod.GET.equals(method) || HttpMethod.HEAD.equals(method);
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
