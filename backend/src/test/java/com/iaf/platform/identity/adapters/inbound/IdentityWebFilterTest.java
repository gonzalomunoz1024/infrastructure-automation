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

import com.iaf.platform.common.domain.Hashes;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.identity.application.CurrentIdentity;
import com.iaf.platform.identity.application.ServiceScopes;
import com.iaf.platform.identity.domain.ServiceAccount;
import com.iaf.platform.identity.ports.outbound.ServiceAccountStore;
import com.iaf.platform.identity.ports.outbound.TokenVerifier;
import com.iaf.platform.identity.ports.outbound.UserDirectory;

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
        ServiceAccount account = new ServiceAccount("sa_ci", "ci-bot", List.of("ops"), List.of("team-compute"), Hashes.sha256("iaf_secret"), Instant.now());
        when(accounts.byTokenHash(Hashes.sha256("iaf_secret"))).thenReturn(Optional.of(account));
        IdentityWebFilter filter = new IdentityWebFilter(props, accounts, users, Optional.empty());
        Identity who = run(filter, MockServerHttpRequest.get("/api/v1/me").header("Authorization", "Bearer iaf_secret").build()).who();
        assertThat(who).isEqualTo(account.identity());
        assertThat(who.kind()).isEqualTo(Identity.Kind.SERVICE);
    }

    @Test
    void aServiceAccountsScopesGoAlongForTheScopeFilterAndTheCodeBehindIt() {
        ServiceAccount account = new ServiceAccount("sa_devin", "devin", List.of(), List.of(), Hashes.sha256("iaf_devin"), Instant.now(),
                List.of("registry:read", "workflows:validate", "workflows:dry-run"));
        when(accounts.byTokenHash(Hashes.sha256("iaf_devin"))).thenReturn(Optional.of(account));
        IdentityWebFilter filter = new IdentityWebFilter(props, accounts, users, Optional.empty());
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/me").header("Authorization", "Bearer iaf_devin").build());
        AtomicReference<Object> inContext = new AtomicReference<>();
        filter.filter(exchange, ex -> reactor.core.publisher.Mono.deferContextual(ctx -> {
            inContext.set(ctx.getOrDefault(ServiceScopes.ATTRIBUTE, null));
            return reactor.core.publisher.Mono.<Void>empty();
        })).block();
        List<String> scopes = exchange.getAttribute(ServiceScopes.ATTRIBUTE);
        assertThat(scopes).isEqualTo(List.of("registry:read", "workflows:validate", "workflows:dry-run"));
        assertThat(inContext.get()).isEqualTo(List.of("registry:read", "workflows:validate", "workflows:dry-run"));

        MockServerWebExchange person = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/me").build());
        filter.filter(person, ex -> reactor.core.publisher.Mono.empty()).block();
        Object none = person.getAttribute(ServiceScopes.ATTRIBUTE);
        assertThat(none).isNull();
    }

    @Test
    void aSignedWebhookComesInWithoutACredentialAsAnIdentityThatMayChangeNothing() {
        props.getIdentity().setMode("oidc");
        IdentityWebFilter filter = new IdentityWebFilter(props, accounts, users, Optional.empty());
        Outcome webhook = run(filter, MockServerHttpRequest.post("/api/v1/change-requests:github-webhook").build());
        assertThat(webhook.who()).isEqualTo(IdentityWebFilter.WEBHOOK);
        assertThat(webhook.who().admin()).isFalse();
        assertThat(webhook.who().teams()).isEmpty();
        assertThat(run(filter, MockServerHttpRequest.get("/api/v1/change-requests:github-webhook").build()).status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(run(filter, MockServerHttpRequest.post("/api/v1/change-requests/cr_1:merge").build()).status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(run(filter, MockServerHttpRequest.post("/api/v1/change-requests:github-webhook").header("Authorization", "Bearer forged").build()).status())
                .as("a presented credential is judged as usual").isEqualTo(HttpStatus.UNAUTHORIZED);

        props.getIdentity().setMode("dev");
        assertThat(run(filter, MockServerHttpRequest.post("/api/v1/change-requests:github-webhook").header("X-User", "gonzalo").build()).who())
                .as("never the dev user either").isEqualTo(IdentityWebFilter.WEBHOOK);
    }

    @Test
    void oidcModeNeedsAVerifiedTokenAndIgnoresTheDevHeader() {
        props.getIdentity().setMode("oidc");
        TokenVerifier verifier = token -> "good".equals(token) ? Mono.just(ANA) : Mono.empty();
        IdentityWebFilter filter = new IdentityWebFilter(props, accounts, users, Optional.of(verifier));
        assertThat(run(filter, MockServerHttpRequest.get("/api/v1/me").header("Authorization", "Bearer good").build()).who()).isEqualTo(ANA);
        assertThat(run(filter, MockServerHttpRequest.get("/api/v1/me").header("Authorization", "Bearer bad").build()).status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(run(filter, MockServerHttpRequest.get("/api/v1/me").header("X-User", "gonzalo").build()).status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** Whether the request got past the filter. */
    private static boolean passes(IdentityWebFilter filter, MockServerHttpRequest request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicReference<Boolean> reached = new AtomicReference<>(false);
        filter.filter(exchange, ex -> Mono.fromRunnable(() -> reached.set(true))).block();
        return reached.get() && exchange.getResponse().getStatusCode() != HttpStatus.UNAUTHORIZED;
    }

    @Test
    void aSharedEvidenceLinkOpensWithoutACallerAsAnIdentityThatHoldsNothing() {
        props.getIdentity().setMode("oidc");
        TokenVerifier verifier = token -> "good".equals(token) ? Mono.just(ANA) : Mono.empty();
        IdentityWebFilter filter = new IdentityWebFilter(props, accounts, users, Optional.of(verifier));

        Outcome shared = run(filter, MockServerHttpRequest.get("/api/v1/audit/shared/shr_4f2a9c").build());
        assertThat(shared.status()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(shared.who()).isEqualTo(IdentityWebFilter.SHARED_LINK);
        assertThat(shared.who().admin()).isFalse();
        assertThat(shared.who().teams()).isEmpty();
        assertThat(shared.who().roles()).isEmpty();
        // a signed-in caller stays who they are; a credential that does not verify is still a 401
        assertThat(run(filter, MockServerHttpRequest.get("/api/v1/audit/shared/shr_4f2a9c").header("Authorization", "Bearer good").build()).who()).isEqualTo(ANA);
        assertThat(run(filter, MockServerHttpRequest.get("/api/v1/audit/shared/shr_4f2a9c").header("Authorization", "Bearer bad").build()).status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // only the read of a link: the record itself, other audit reads and writes still need a caller
        assertThat(run(filter, MockServerHttpRequest.post("/api/v1/audit/shared/shr_4f2a9c").build()).status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(run(filter, MockServerHttpRequest.get("/api/v1/audit/job_1").build()).status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(run(filter, MockServerHttpRequest.get("/api/v1/audit/shared/shr_1/export").build()).status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(run(filter, MockServerHttpRequest.get("/api/v1/audit/shared/").build()).status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(run(filter, MockServerHttpRequest.get("/api/v1/audit/shared/..%2Fjob_1").build()).status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void theSwaggerUiPageAndItsAssetsLoadWithoutACallerAndTheApiDoesNot() {
        props.getIdentity().setMode("oidc");
        IdentityWebFilter filter = new IdentityWebFilter(props, accounts, users, Optional.empty());

        for (String path : List.of("/swagger-ui", "/swagger-ui/", "/swagger-ui/index.html", "/webjars/swagger-ui/swagger-ui-bundle.js")) {
            assertThat(passes(filter, MockServerHttpRequest.get(path).build())).as(path).isTrue();
        }
        assertThat(passes(filter, MockServerHttpRequest.head("/swagger-ui/index.html").build())).isTrue();
        assertThat(passes(filter, MockServerHttpRequest.get("/api/v1/openapi.json").build())).isFalse();
        assertThat(passes(filter, MockServerHttpRequest.post("/swagger-ui/index.html").build())).isFalse();
        assertThat(passes(filter, MockServerHttpRequest.get("/webjars/other/app.js").build())).isFalse();
        assertThat(passes(filter, MockServerHttpRequest.get("/swagger-ui/../api/v1/jobs").build())).isFalse();
        assertThat(passes(filter, MockServerHttpRequest.get("/swagger-uix").build())).isFalse();
        assertThat(passes(filter, MockServerHttpRequest.get("/swagger-ui/index.html").header("X-User", "gonzalo").build())).isTrue();
    }
}
