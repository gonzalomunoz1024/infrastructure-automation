package com.iaf.console.platform.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class RequestOptionsTest {

    @Test
    void writesOnlyWhatIsSet() {
        HttpHeaders headers = new HttpHeaders();
        RequestOptions.none().apply(headers);
        assertThat(headers).isEmpty();

        RequestOptions options = RequestOptions.console("jws", "req-1", "key-1")
                .withResourceWfrn("wfrn:wf:compute:global:123456789012:vm/01abc")
                .withLabel("tab", "console-4f2a")
                .withTraceparent("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01")
                .withWorkflowVersion("2.0.1");
        headers = new HttpHeaders();
        options.apply(headers);
        assertThat(headers.getFirst(PlatformHeaders.ON_BEHALF_OF)).isEqualTo("jws");
        assertThat(headers.getFirst(PlatformHeaders.CONSOLE_REQUEST_ID)).isEqualTo("req-1");
        assertThat(headers.getFirst(PlatformHeaders.IDEMPOTENCY_KEY)).isEqualTo("key-1");
        assertThat(headers.getFirst(PlatformHeaders.RESOURCE_WFRN)).isEqualTo("wfrn:wf:compute:global:123456789012:vm/01abc");
        assertThat(headers.getFirst("X-Job-Label-tab")).isEqualTo("console-4f2a");
        assertThat(headers.getFirst(PlatformHeaders.TRACEPARENT)).startsWith("00-0af76519");
        assertThat(headers.getFirst(PlatformHeaders.WORKFLOW_VERSION)).isEqualTo("2.0.1");
    }

    @Test
    void checksLabelsAndKeys() {
        assertThatThrownBy(() -> RequestOptions.none().withLabel("Tab", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RequestOptions.none().withLabel("a".repeat(33), "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RequestOptions.none().withLabel("tab", "x".repeat(129))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RequestOptions.none().withIdempotencyKey("k".repeat(256))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RequestOptions.none().withIdempotencyKey(" ")).isInstanceOf(IllegalArgumentException.class);
        RequestOptions sixteen = RequestOptions.none();
        for (int i = 0; i < 16; i++) {
            sixteen = sixteen.withLabel("l" + i, "v");
        }
        RequestOptions full = sixteen;
        assertThat(full.labels()).hasSize(16);
        assertThatThrownBy(() -> full.withLabel("one-more", "v")).isInstanceOf(IllegalArgumentException.class);
    }
}
