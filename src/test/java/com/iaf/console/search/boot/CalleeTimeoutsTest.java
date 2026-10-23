package com.iaf.console.search.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.search.TestClock;
import com.iaf.console.search.index.adapters.opensearch.OpenSearchIndex;
import com.iaf.console.search.index.adapters.tokens.ServiceCredentials;
import com.iaf.console.search.index.domain.Fixtures;
import com.iaf.console.search.index.ports.outbound.AccountsPort;
import com.iaf.console.search.index.ports.outbound.StateServicePort;

import io.netty.handler.timeout.ReadTimeoutException;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * The state service, the identity service and OpenSearch are given up on when they stay silent
 * past their configured timeouts, so none holds up a poll for good; a delete by query, which
 * OpenSearch answers only once it is done, has longer.
 */
class CalleeTimeoutsTest {

    private static final Duration TIMEOUT = Duration.ofMillis(300);

    /** Answers a delete by query after a second, and nothing else ever. */
    private final DisposableServer silent = HttpServer.create().host("127.0.0.1").port(0)
            .handle((request, response) -> request.uri().contains("/_delete_by_query")
                    ? Mono.delay(Duration.ofSeconds(1))
                            .then(response.header("Content-Type", "application/json").sendString(Mono.just("{\"deleted\":3}")).then())
                    : Mono.never())
            .bindNow();
    private final AccountScope scope = AccountScope.of(Fixtures.ACCOUNT, "global", "test");

    @AfterEach
    void stop() {
        silent.disposeNow();
    }

    private SearchProperties properties() {
        String url = "http://127.0.0.1:" + silent.port();
        return new SearchProperties(null,
                new SearchProperties.SearchSettings(null, new SearchProperties.OpenSearchSettings(url, null, null, null, null, TIMEOUT), null),
                new SearchProperties.StateSettings(url, null, TIMEOUT), null, new SearchProperties.IdentitySettings(url, TIMEOUT), null, null, null,
                null);
    }

    private OpenSearchIndex openSearch() {
        return new IndexConfiguration.OpenSearch().openSearchIndex(WebClient.builder(), properties(), new TestClock(Fixtures.T0));
    }

    @Test
    void theStateServiceIsGivenUpOnAfterItsTimeout() {
        StateServicePort state = new PlatformConfiguration().stateService(WebClient.builder(), properties(), ServiceCredentials.none());
        assertThatThrownBy(() -> state.find(scope, Fixtures.VM).block(Duration.ofSeconds(10))).hasRootCauseInstanceOf(ReadTimeoutException.class);
    }

    @Test
    void theIdentityServiceIsGivenUpOnAfterItsTimeout() {
        AccountsPort accounts = new PlatformConfiguration().accounts(WebClient.builder(), properties(), ServiceCredentials.none());
        assertThatThrownBy(() -> accounts.listAccountIds().collectList().block(Duration.ofSeconds(10))).hasRootCauseInstanceOf(ReadTimeoutException.class);
    }

    @Test
    void openSearchIsGivenUpOnAfterItsTimeoutButADeleteByQueryRunsItsCourse() {
        OpenSearchIndex index = openSearch();
        assertThatThrownBy(() -> index.listAccountIds().collectList().block(Duration.ofSeconds(10))).hasRootCauseInstanceOf(ReadTimeoutException.class);
        assertThat(index.expireTombstones(Fixtures.T0).block(Duration.ofSeconds(10))).as("answered after a second, past the timeout").isEqualTo(3);
    }
}
