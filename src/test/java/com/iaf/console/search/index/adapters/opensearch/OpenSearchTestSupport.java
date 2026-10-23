package com.iaf.console.search.index.adapters.opensearch;

import org.springframework.web.reactive.function.client.WebClient;

/**
 * Where the OpenSearch adapter tests run: the cluster {@code CONSOLE_TEST_OPENSEARCH_URL} names
 * (CI starts one as a service container), else nowhere (the tests are skipped).
 */
public final class OpenSearchTestSupport {

    public static final String URL_VARIABLE = "CONSOLE_TEST_OPENSEARCH_URL";

    private OpenSearchTestSupport() {
    }

    /** What {@code @EnabledIf} asks. */
    public static boolean available() {
        return url() != null;
    }

    public static String url() {
        String url = System.getenv(URL_VARIABLE);
        return url == null || url.isBlank() ? null : url.trim();
    }

    public static WebClient client() {
        return WebClient.builder().baseUrl(url()).codecs(c -> c.defaultCodecs().maxInMemorySize(32 * 1024 * 1024)).build();
    }
}
