package com.iaf.platform.identity.adapters.inbound;

import java.util.List;
import java.util.Optional;
import java.util.Set;

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
import com.iaf.platform.identity.application.ServiceScopes;
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
 * {@code X-User} header or the default user. A service account's scopes go along with it
 * ({@link ServiceScopes}) for the scope filter and the code behind it. A signed webhook (POST on
 * one of {@link #SIGNED_WEBHOOKS}, without a credential) runs as {@link #WEBHOOK}, an identity
 * that may change nothing; its controller checks the signature and decides as whom it acts.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class IdentityWebFilter implements WebFilter {

    public static final String ATTRIBUTE = "platform.identity";
    /** Endpoints whose callers prove themselves with a signature over the body instead of a credential. */
    public static final Set<String> SIGNED_WEBHOOKS = Set.of("/api/v1/change-requests:github-webhook");
    public static final Identity WEBHOOK = new Identity("webhook", "Webhook", null, List.of(), List.of(), false, Identity.Kind.SERVICE);

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
        List<String> scopes = exchange.getAttribute(ServiceScopes.ATTRIBUTE);
        return chain.filter(exchange).contextWrite(ctx -> ServiceScopes.with(CurrentIdentity.with(ctx, who), scopes));
    }

    private Optional<Identity> resolve(ServerWebExchange exchange) {
        String auth = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth == null && "POST".equals(exchange.getRequest().getMethod().name())
                && SIGNED_WEBHOOKS.contains(exchange.getRequest().getPath().pathWithinApplication().value())) {
            return Optional.of(WEBHOOK);
        }
        if (auth != null && auth.startsWith("Bearer ")) {
            String token = auth.substring(7).trim();
            if (token.startsWith("iaf_")) {
                return serviceAccounts.byTokenHash(WorkflowYaml.sha256(token)).map(a -> {
                    exchange.getAttributes().put(ServiceScopes.ATTRIBUTE, a.grantedScopes());
                    return a.identity();
                });
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
