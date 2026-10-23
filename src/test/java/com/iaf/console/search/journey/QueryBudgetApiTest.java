package com.iaf.console.search.journey;

import static com.iaf.console.search.index.domain.Fixtures.ACCOUNT;
import static com.iaf.console.search.index.domain.Fixtures.OTHER_ACCOUNT;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.iaf.console.search.SearchIndexerApplication;

/** Each account has a query budget: a burst beyond it waits, told how long, and another account is not touched. */
@SpringBootTest(classes = SearchIndexerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "console.search.engine=memory", "console.ingest.enabled=false", "console.auth.dev-headers=true", "console.search.budget.per-second=0.001",
        "console.search.budget.burst=2" })
class QueryBudgetApiTest {

    @LocalServerPort
    int port;

    private WebTestClient web() {
        return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).responseTimeout(Duration.ofSeconds(30)).build();
    }

    private WebTestClient.ResponseSpec search(String account, String path) {
        return web().get().uri(path).header("X-Console-Account", account).header("X-Console-User", "dana").exchange();
    }

    @Test
    void aBurstBeyondTheBudgetWaits() {
        search(ACCOUNT, "/v1/search?q=web").expectStatus().isOk();
        search(ACCOUNT, "/v1/counts").expectStatus().isOk();
        String retryAfter = search(ACCOUNT, "/v1/search?q=web").expectStatus().isEqualTo(429).expectHeader().exists(HttpHeaders.RETRY_AFTER)
                .returnResult(String.class).getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        assertThat(Long.parseLong(retryAfter)).isPositive();
        search(ACCOUNT, "/v1/suggest?q=we").expectStatus().isEqualTo(429);
        search(ACCOUNT, "/v1/counts").expectStatus().isEqualTo(429);
        search(OTHER_ACCOUNT, "/v1/search?q=web").expectStatus().isOk();
        // the status is not a query and spends nothing
        search(ACCOUNT, "/v1/index/status").expectStatus().isOk();
    }
}
