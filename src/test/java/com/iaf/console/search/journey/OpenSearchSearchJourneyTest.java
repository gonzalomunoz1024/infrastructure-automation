package com.iaf.console.search.journey;

import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.iaf.console.search.index.adapters.opensearch.OpenSearchIndex;
import com.iaf.console.search.index.adapters.opensearch.OpenSearchTestSupport;
import com.iaf.console.search.support.StubStateServer;

import reactor.core.publisher.Mono;

/** The journey on OpenSearch ({@code CONSOLE_TEST_OPENSEARCH_URL}), on an index of its own; skipped without one. */
@EnabledIf("com.iaf.console.search.index.adapters.opensearch.OpenSearchTestSupport#available")
class OpenSearchSearchJourneyTest extends SearchJourneyTest {

    static final StubStateServer STATE = seeded();
    static final String INDEX = "console-journey-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toLowerCase(Locale.ROOT);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        common(registry, STATE);
        registry.add("console.search.engine", () -> "opensearch");
        registry.add("console.search.opensearch.url", OpenSearchTestSupport::url);
        registry.add("console.search.opensearch.index", () -> INDEX);
        registry.add("console.search.opensearch.refresh", () -> "wait_for");
    }

    @Override
    protected StubStateServer state() {
        return STATE;
    }

    @AfterAll
    static void dropTheIndex() {
        STATE.close();
        if (OpenSearchTestSupport.available()) {
            OpenSearchTestSupport.client().delete().uri("/{index}", INDEX + "-v" + OpenSearchIndex.MAPPING_VERSION)
                    .exchangeToMono(r -> r.releaseBody()).onErrorResume(e -> Mono.empty()).block();
        }
    }
}
