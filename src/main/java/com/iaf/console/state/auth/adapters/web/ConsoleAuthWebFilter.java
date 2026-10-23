package com.iaf.console.state.auth.adapters.web;

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

import com.iaf.console.state.auth.domain.ConsolePrincipal;

import reactor.core.publisher.Mono;

/**
 * Authenticates every call but the actuator's: a bearer JWT the verifier accepts, or, in the demo
 * and dev profiles only, the dev headers {@code X-Console-Account}, {@code X-Console-User},
 * {@code X-Console-Service} and {@code X-Console-Region} that stand in for the BFF. Anything else
 * is a 401. The principal rides on the exchange for the controllers.
 */
public final class ConsoleAuthWebFilter implements WebFilter, Ordered {

    public static final String ACCOUNT_HEADER = "X-Console-Account";
    public static final String USER_HEADER = "X-Console-User";
    public static final String SERVICE_HEADER = "X-Console-Service";
    public static final String REGION_HEADER = "X-Console-Region";
    public static final String GROUPS_HEADER = "X-Console-Groups";

    private static final Logger log = LoggerFactory.getLogger(ConsoleAuthWebFilter.class);

    private final JwtVerifier verifier;
    private final boolean devHeaders;
    private final String defaultRegion;

    public ConsoleAuthWebFilter(JwtVerifier verifier, boolean devHeaders, String defaultRegion) {
        this.verifier = verifier;
        this.devHeaders = devHeaders;
        this.defaultRegion = defaultRegion;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (path.startsWith("/actuator") || path.startsWith("/api/v1/") || path.startsWith("/.well-known/")) {
            // the actuator, and the embedded fake platform of the demo profile, do their own authentication
            return chain.filter(exchange);
        }
        HttpHeaders headers = exchange.getRequest().getHeaders();
        String authorization = headers.getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return verifier.verify(authorization.substring(7).trim())
                    .flatMap(principal -> {
                        exchange.getAttributes().put(ConsolePrincipal.ATTRIBUTE, principal);
                        return chain.filter(exchange);
                    })
                    .onErrorResume(JwtVerifier.InvalidToken.class, e -> {
                        log.info("rejected token: {}", e.getMessage());
                        return unauthorized(exchange, "unauthorized");
                    });
        }
        if (devHeaders) {
            String account = headers.getFirst(ACCOUNT_HEADER);
            String service = headers.getFirst(SERVICE_HEADER);
            if (account != null || service != null) {
                String user = headers.getFirst(USER_HEADER);
                String region = headers.getFirst(REGION_HEADER);
                List<String> groups = headers.getFirst(GROUPS_HEADER) == null ? List.of() : List.of(headers.getFirst(GROUPS_HEADER).split(","));
                ConsolePrincipal principal = new ConsolePrincipal(user != null ? user : service != null ? service : "dev",
                        account == null || account.isBlank() ? null : account, region != null ? region : defaultRegion, groups, service);
                exchange.getAttributes().put(ConsolePrincipal.ATTRIBUTE, principal);
                return chain.filter(exchange);
            }
        }
        return unauthorized(exchange, "unauthorized");
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
