package com.iaf.platform.identity.adapters.inbound;

import java.util.List;
import java.util.Optional;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
 * dev header {@code X-User}, the configured default user. In oidc mode an unknown bearer token is
 * a 401; in dev mode nothing is rejected.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class IdentityWebFilter implements WebFilter {

    public static final String ATTRIBUTE = "platform.identity";

    private final PlatformProperties props;
    private final ServiceAccountStore serviceAccounts;
    private final UserDirectory users;
    private final Optional<TokenVerifier> tokens;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        Optional<Identity> resolved = resolve(exchange);
        if (resolved.isEmpty()) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
        Identity who = resolved.get();
        exchange.getAttributes().put(ATTRIBUTE, who);
        return chain.filter(exchange).contextWrite(ctx -> CurrentIdentity.with(ctx, who));
    }

    private Optional<Identity> resolve(ServerWebExchange exchange) {
        String auth = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth != null && auth.startsWith("Bearer ")) {
            String token = auth.substring(7).trim();
            if (token.startsWith("iaf_")) {
                return serviceAccounts.byTokenHash(WorkflowYaml.sha256(token)).map(a -> a.identity());
            }
            if (tokens.isPresent()) {
                return tokens.get().verify(token);
            }
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
