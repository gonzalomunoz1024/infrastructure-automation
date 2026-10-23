package com.iaf.console.identity.common.auth;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.kit.correlation.Correlation;
import com.iaf.console.kit.errors.Errors;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/**
 * Authenticates every call but the actuator's: the internal JWT as a bearer token, the bundle token
 * on the bundle path, or, when {@code console.identity.auth.dev-headers} is on (the demo), the dev
 * headers {@code X-User}, {@code X-Account}, {@code X-Groups}, {@code X-Region} and {@code X-Service}.
 * Anything else is a 401 with the contract's error body.
 */
public final class AuthWebFilter implements WebFilter, Ordered {

    /** The one path the bundle token opens. */
    public static final String BUNDLE_PATH = "/internal/opa/bundles/console-authz.tar.gz";

    private final InternalJwts.Verifier verifier;
    private final AuthProperties properties;
    private final ObjectMapper json;
    private final String region;

    public AuthWebFilter(InternalJwts.Verifier verifier, AuthProperties properties, ObjectMapper json, String region) {
        this.verifier = verifier;
        this.properties = properties;
        this.json = json;
        this.region = region;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().pathWithinApplication().value();
        if (path.startsWith("/actuator")) {
            return chain.filter(exchange);
        }
        return authenticate(request, path)
                .flatMap(caller -> {
                    exchange.getAttributes().put(Caller.ATTRIBUTE, caller);
                    return chain.filter(exchange);
                })
                .onErrorResume(UnauthenticatedException.class, e -> unauthorized(exchange, e.getMessage()));
    }

    private Mono<Caller> authenticate(ServerHttpRequest request, String path) {
        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization != null && !authorization.isBlank()) {
            if (!authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
                return Mono.error(new UnauthenticatedException("only a bearer token is accepted"));
            }
            String token = authorization.substring(7).trim();
            if (token.isEmpty()) {
                return Mono.error(new UnauthenticatedException("the bearer token is empty"));
            }
            if (BUNDLE_PATH.equals(path) && properties.bundleToken() != null && constantTimeEquals(properties.bundleToken(), token)) {
                return Mono.just(Caller.internal("opa", region));
            }
            return verifier.verify(token);
        }
        if (properties.devHeaders()) {
            return devCaller(request);
        }
        return Mono.error(new UnauthenticatedException("a bearer token is required"));
    }

    private Mono<Caller> devCaller(ServerHttpRequest request) {
        HttpHeaders headers = request.getHeaders();
        String service = headers.getFirst("X-Service");
        String user = headers.getFirst("X-User");
        String regionHeader = headers.getFirst("X-Region");
        String callRegion = regionHeader == null || regionHeader.isBlank() ? region : regionHeader.trim();
        if (service != null && !service.isBlank() && (user == null || user.isBlank())) {
            return Mono.just(Caller.internal(service.trim(), callRegion));
        }
        if (user == null || user.isBlank()) {
            return Mono.error(new UnauthenticatedException("a bearer token, or the dev headers X-User and X-Account, are required"));
        }
        String account = headers.getFirst("X-Account");
        if (account == null || !AccountScope.isAccountId(account.trim())) {
            return Mono.error(new UnauthenticatedException("the dev header X-Account must name a twelve-digit account"));
        }
        return Mono.just(Caller.user(user.trim(), account.trim(), callRegion, names(headers.getFirst("X-Groups")), names(headers.getFirst("X-Roles")),
                service == null ? "dev" : service.trim(), null, null));
    }

    /** A comma-separated dev header as names. */
    private static List<String> names(String header) {
        return header == null || header.isBlank() ? List.of() : Arrays.stream(header.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"console\"");
        byte[] body;
        try {
            body = json.writeValueAsBytes(Errors.of(message).withCorrelationId(Correlation.of(exchange)));
        } catch (JsonProcessingException e) {
            body = "{\"error\":\"unauthenticated\"}".getBytes(StandardCharsets.UTF_8);
        }
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(x, y);
    }
}
