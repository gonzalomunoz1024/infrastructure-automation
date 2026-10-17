package com.iaf.platform.identity.adapters.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;

import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.identity.application.CurrentIdentity;
import com.iaf.platform.identity.domain.ServiceAccount;
import com.iaf.platform.identity.ports.outbound.ServiceAccountStore;
import com.iaf.platform.identity.ports.outbound.TokenVerifier;
import com.iaf.platform.identity.ports.outbound.UserDirectory;
import com.iaf.platform.workflows.domain.WorkflowYaml;

import reactor.core.publisher.Mono;

/** Who a request runs as: tokens first, then the dev header, and a presented credential never falls back. */
class IdentityWebFilterTest {

    private static final Identity GONZALO = new Identity("gonzalo", "Gonzalo", "gonzalo@example.com", List.of("team-compute"), List.of("admin"), true, Identity.Kind.USER);
    private static final Identity ANA = new Identity("ana", "Ana", "ana@example.com", List.of("team-data"), List.of("ops"), false, Identity.Kind.USER);

    private final PlatformProperties props = new PlatformProperties();
    private final ServiceAccountStore accounts = mock(ServiceAccountStore.class);
    private final UserDirectory users = mock(UserDirectory.class);

    IdentityWebFilterTest() {
        when(users.byId(anyString())).thenReturn(Optional.empty());
        when(users.byId("gonzalo")).thenReturn(Optional.of(GONZALO));
        when(users.byId("ana")).thenReturn(Optional.of(ANA));
        when(accounts.byTokenHash(anyString())).thenReturn(Optional.empty());
    }

    private record Outcome(HttpStatus status, Identity who) {
    }

    private Outcome run(IdentityWebFilter filter, MockServerHttpRequest request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicReference<Identity> seen = new AtomicReference<>();
        WebFilterChain chain = ex -> CurrentIdentity.current().doOnNext(seen::set).then();
        filter.filter(exchange, chain).block();
        return new Outcome((HttpStatus) exchange.getResponse().getStatusCode(), seen.get());
    }

    @Test
    void devModeUsesTheHeaderOrTheDefaultUser() {
        IdentityWebFilter filter = new IdentityWebFilter(props, accounts, users, Optional.empty());
        assertThat(run(filter, MockServerHttpRequest.get("/api/v1/me").build()).who()).isEqualTo(GONZALO);
        assertThat(run(filter, MockServerHttpRequest.get("/api/v1/me").header("X-User", "ana").build()).who()).isEqualTo(ANA);
        Identity stranger = run(filter, MockServerHttpRequest.get("/api/v1/me").header("X-User", "visitor").build()).who();
        assertThat(stranger.id()).isEqualTo("visitor");
        assertThat(stranger.admin()).isFalse();
        assertThat(stranger.roles()).containsExactly("user");
    }

    @Test
    void anUnverifiableBearerTokenIsRejectedEvenInDevMode() {
        IdentityWebFilter filter = new IdentityWebFilter(props, accounts, users, Optional.empty());
        Outcome outcome = run(filter, MockServerHttpRequest.get("/api/v1/me").header("Authorization", "Bearer not-a-token").header("X-User", "gonzalo").build());
        assertThat(outcome.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(outcome.who()).isNull();
        assertThat(run(filter, MockServerHttpRequest.get("/api/v1/me").header("Authorization", "Bearer iaf_unknown").build()).status())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(run(filter, MockServerHttpRequest.get("/api/v1/me").header("Authorization", "Bearer ").build()).status())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aServiceAccountTokenResolvesToTheAccount() {
        ServiceAccount account = new ServiceAccount("sa_ci", "ci-bot", List.of("ops"), List.of("team-compute"), WorkflowYaml.sha256("iaf_secret"), Instant.now());
        when(accounts.byTokenHash(WorkflowYaml.sha256("iaf_secret"))).thenReturn(Optional.of(account));
        IdentityWebFilter filter = new IdentityWebFilter(props, accounts, users, Optional.empty());
        Identity who = run(filter, MockServerHttpRequest.get("/api/v1/me").header("Authorization", "Bearer iaf_secret").build()).who();
        assertThat(who).isEqualTo(account.identity());
        assertThat(who.kind()).isEqualTo(Identity.Kind.SERVICE);
    }

    @Test
    void oidcModeNeedsAVerifiedTokenAndIgnoresTheDevHeader() {
        props.getIdentity().setMode("oidc");
        TokenVerifier verifier = token -> "good".equals(token) ? Optional.of(ANA) : Optional.empty();
        IdentityWebFilter filter = new IdentityWebFilter(props, accounts, users, Optional.of(verifier));
        assertThat(run(filter, MockServerHttpRequest.get("/api/v1/me").header("Authorization", "Bearer good").build()).who()).isEqualTo(ANA);
        assertThat(run(filter, MockServerHttpRequest.get("/api/v1/me").header("Authorization", "Bearer bad").build()).status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(run(filter, MockServerHttpRequest.get("/api/v1/me").header("X-User", "gonzalo").build()).status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
