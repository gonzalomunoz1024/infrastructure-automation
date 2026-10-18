package com.iaf.platform.identity.adapters.inbound;

import java.util.List;
import java.util.Optional;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.identity.application.CurrentIdentity;
import com.iaf.platform.identity.ports.outbound.ServiceAccountStore;
import com.iaf.platform.identity.ports.outbound.TokenVerifier;
import com.iaf.platform.identity.ports.outbound.UserDirectory;
import com.iaf.platform.workflows.domain.WorkflowYaml;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * Resolves who is calling and puts it in the Reactor context. Order of precedence: a service
 * account token ({@code Authorization: Bearer iaf_…}), an OIDC bearer token (profile oidc), the
 * dev header {@code X-User}, the configured default user. A bearer token that cannot be verified
 * is always a 401, in every mode: a caller who presents a credential never falls back to the dev
 * identity. In oidc mode a request without a token is a 401 too; in dev mode it is the
 * {@code X-User} header or the default user. The Swagger UI page and its assets, which hold no
 * data, load without a caller.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class IdentityWebFilter implements WebFilter {

    public static final String ATTRIBUTE = "platform.identity";
    static final String PUBLIC_PAGE = "/swagger-ui";
    static final String PUBLIC_ASSETS = "/webjars/swagger-ui/";

    private final PlatformProperties props;
    private final ServiceAccountStore serviceAccounts;
    private final UserDirectory users;
    private final Optional<TokenVerifier> tokens;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        Optional<Identity> resolved = resolve(exchange);
        if (resolved.isEmpty() && isPublicPage(exchange.getRequest())) {
            return chain.filter(exchange);
        }
        if (resolved.isEmpty()) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
        Identity who = resolved.get();
        exchange.getAttributes().put(ATTRIBUTE, who);
        return chain.filter(exchange).contextWrite(ctx -> CurrentIdentity.with(ctx, who));
    }

    /**
     * The Swagger UI page and its assets: static, holding no data, they load without a caller (the
     * API they call does not). Only GET and HEAD, and never a path that climbs or carries escapes.
     */
    static boolean isPublicPage(ServerHttpRequest request) {
        HttpMethod method = request.getMethod();
        if (method != HttpMethod.GET && method != HttpMethod.HEAD) {
            return false;
        }
        String path = request.getPath().pathWithinApplication().value();
        if (path.contains("..") || path.contains("%") || path.contains(";") || path.contains("//")) {
            return false;
        }
        return path.equals(PUBLIC_PAGE) || path.startsWith(PUBLIC_PAGE + "/") || path.startsWith(PUBLIC_ASSETS);
    }

    private Optional<Identity> resolve(ServerWebExchange exchange) {
        String auth = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth != null && auth.startsWith("Bearer ")) {
            String token = auth.substring(7).trim();
            if (token.startsWith("iaf_")) {
                return serviceAccounts.byTokenHash(WorkflowYaml.sha256(token)).map(a -> a.identity());
            }
            // an OIDC token when a verifier is configured; anything else presented as a bearer is rejected
            return tokens.isPresent() && !token.isEmpty() ? tokens.get().verify(token) : Optional.empty();
        }
        boolean oidc = "oidc".equals(props.getIdentity().getMode());
        if (oidc) {
            return Optional.empty();
        }
        String header = exchange.getRequest().getHeaders().getFirst("X-User");
        String id = header == null || header.isBlank() ? props.getIdentity().getDefaultUser() : header.trim();
        return Optional.of(users.byId(id).orElseGet(() -> new Identity(id, id, null, List.of(), List.of("user"), false, Identity.Kind.USER)));
    }
}
