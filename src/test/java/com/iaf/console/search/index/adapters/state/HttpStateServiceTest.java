package com.iaf.console.search.index.adapters.state;

import static com.iaf.console.search.index.domain.Fixtures.ACCOUNT;
import static com.iaf.console.search.index.domain.Fixtures.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.search.TestClock;
import com.iaf.console.search.index.adapters.tokens.ClientAssertion;
import com.iaf.console.search.index.adapters.tokens.ServiceCredentials;
import com.iaf.console.search.index.adapters.tokens.ServiceTokens;
import com.iaf.console.search.index.domain.Fixtures;
import com.iaf.console.search.index.domain.ResourceState;
import com.iaf.console.search.index.domain.StateListing;
import com.iaf.console.search.index.domain.StateResource;
import com.iaf.console.search.support.StubStateServer;
import com.iaf.console.search.support.StubTokenServer;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;

/** The state service client: paging to the end, the tombstone as a 410, nothing as a 404, and how it names itself. */
class HttpStateServiceTest {

    private final StubStateServer stub = StubStateServer.start();
    private final StubTokenServer bff = StubTokenServer.start();
    private final AccountScope scope = AccountScope.of(ACCOUNT, "global", "test");

    @AfterEach
    void stop() {
        stub.close();
        bff.close();
    }

    private HttpStateService client(ServiceCredentials credentials) {
        return new HttpStateService(WebClient.builder().baseUrl(stub.url()).filter(credentials.filterFor("console-state-service")).build());
    }

    private ServiceCredentials bffTokens() throws Exception {
        TestClock clock = new TestClock(Instant.parse("2026-10-23T12:00:00Z"));
        String key = new ECKeyGenerator(Curve.P_256).keyID("indexer-1").generate().toJSONString();
        return ServiceCredentials.tokens(new ServiceTokens(WebClient.create(), bff.url(),
                ClientAssertion.signed(key, ServiceCredentials.SERVICE, "console-bff", clock), ServiceCredentials.SERVICE, Duration.ofSeconds(5), clock));
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
        HttpStateService state = client(ServiceCredentials.none());
        assertThatThrownBy(() -> state.find(scope, Fixtures.VM).block()).hasMessageContaining("401");
        assertThat(stub.unauthorized()).as("a refusal is not asked again").isEqualTo(1);
    }

    @Test
    void theBffsTokenForTheStateServiceIsSentAndARefusedOneReplacedOnce() throws Exception {
        stub.data().put(Fixtures.stateResource(Fixtures.VM, "web", 1, "active", Map.of(), Map.of(), T0));
        stub.refuse("tok-1-console-state-service");
        HttpStateService state = client(bffTokens());
        assertThat(state.find(scope, Fixtures.VM).block()).as("the second token gets through").isNotNull();
        assertThat(stub.bearers()).containsExactly("tok-1-console-state-service", "tok-2-console-state-service");
        assertThat(bff.forms()).extracting(f -> f.getFirst("audience")).containsExactly("console-state-service", "console-state-service");
        assertThat(state.list(scope, StateListing.everything()).collectList().block()).hasSize(1);
        assertThat(bff.forms()).as("the new token is kept").hasSize(2);
        stub.refuse("tok-2-console-state-service");
        stub.refuse("tok-3-console-state-service");
        assertThatThrownBy(() -> state.find(scope, Fixtures.VM).block()).as("one new token, not a loop").hasMessageContaining("401");
        assertThat(bff.forms()).hasSize(3);
    }
}
