package com.iaf.console.search.index.adapters.state;

import static com.iaf.console.search.index.domain.Fixtures.ACCOUNT;
import static com.iaf.console.search.index.domain.Fixtures.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.search.index.domain.Fixtures;
import com.iaf.console.search.index.domain.ResourceState;
import com.iaf.console.search.index.domain.StateListing;
import com.iaf.console.search.index.domain.StateResource;
import com.iaf.console.search.support.StubStateServer;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.SignedJWT;

/** The state service client: paging to the end, the tombstone as a 410, nothing as a 404, and how it names itself. */
class HttpStateServiceTest {

    private final StubStateServer stub = StubStateServer.start();
    private final AccountScope scope = AccountScope.of(ACCOUNT, "global", "test");

    @AfterEach
    void stop() {
        stub.close();
    }

    private HttpStateService client(ServiceCredentials credentials) {
        return new HttpStateService(WebClient.builder().baseUrl(stub.url()).build(), credentials);
    }

    @Test
    void listsEveryPageAndReadsOneResource() {
        for (int i = 0; i < 5; i++) {
            stub.data().put(Fixtures.stateResource("wfrn:wf:compute:global:" + ACCOUNT + ":vm/vm-" + i, "web-" + i, 1, "active", Map.of(), Map.of(),
                    T0.plusSeconds(i)));
        }
        stub.data().put(Fixtures.stateResource(Fixtures.OTHERS_VM, "theirs", 1, "active", Map.of(), Map.of(), T0));
        HttpStateService state = client(ServiceCredentials.devHeaders());
        List<StateResource> all = state.list(scope, new StateListing(null, null, null, 2)).collectList().block();
        assertThat(all).extracting(StateResource::wfrn).hasSize(5).allMatch(w -> w.contains(ACCOUNT));
        assertThat(all.get(0).wfrn()).endsWith("vm-0");
        List<StateResource> recent = state.list(scope, StateListing.since(T0.plusSeconds(2))).collectList().block();
        assertThat(recent).hasSize(2);
        StateResource one = state.find(scope, "wfrn:wf:compute:global:" + ACCOUNT + ":vm/vm-3").block();
        assertThat(one.tombstone()).isFalse();
        assertThat(one.version()).isEqualTo(1);
        assertThat(state.find(scope, "wfrn:wf:compute:global:" + ACCOUNT + ":vm/vm-none").blockOptional()).isEmpty();
        stub.data().tombstone("wfrn:wf:compute:global:" + ACCOUNT + ":vm/vm-4", 3, T0.plusSeconds(60));
        StateResource gone = state.find(scope, "wfrn:wf:compute:global:" + ACCOUNT + ":vm/vm-4").block();
        assertThat(gone.tombstone()).isTrue();
        assertThat(gone.version()).isEqualTo(3);
        assertThat(state.list(scope, StateListing.everything().ofState(ResourceState.DELETED)).collectList().block()).isEmpty();
    }

    @Test
    void withoutCredentialsTheStateServiceRefuses() {
        HttpStateService state = client(headers -> {
        });
        assertThatThrownBy(() -> state.find(scope, Fixtures.VM).block()).hasMessageContaining("401");
        assertThat(stub.unauthorized()).isEqualTo(1);
    }

    @Test
    void aSignedServiceTokenNamesThisService() throws Exception {
        ECKey key = new ECKeyGenerator(Curve.P_256).keyID("bff-1").generate();
        Clock clock = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC);
        ServiceCredentials.Signed signed = (ServiceCredentials.Signed) ServiceCredentials.signed(key.toJSONString(), "console-bff",
                List.of("console-state-service", "console"), clock);
        String token = signed.token();
        assertThat(signed.token()).as("reused within its window").isEqualTo(token);
        SignedJWT jwt = SignedJWT.parse(token);
        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.ES256);
        assertThat(jwt.getHeader().getKeyID()).isEqualTo("bff-1");
        assertThat(jwt.verify(new ECDSAVerifier(key.toPublicJWK()))).isTrue();
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("console-bff");
        assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly("console-state-service", "console");
        assertThat(jwt.getJWTClaimsSet().getStringClaim("svc")).isEqualTo(ServiceCredentials.SERVICE);
        assertThat(jwt.getJWTClaimsSet().getStringClaim("acct")).as("a service token names no account").isNull();
        assertThat(jwt.getJWTClaimsSet().getExpirationTime().toInstant()).isEqualTo(clock.instant().plusSeconds(60));
        assertThatThrownBy(() -> ServiceCredentials.signed(key.toPublicJWK().toJSONString(), "console-bff", List.of("console"), clock))
                .hasMessageContaining("private");
        HttpStateService state = client(signed);
        stub.data().put(Fixtures.stateResource(Fixtures.VM, "web", 1, "active", Map.of(), Map.of(), T0));
        assertThat(state.find(scope, Fixtures.VM).block()).isNotNull();
    }
}
