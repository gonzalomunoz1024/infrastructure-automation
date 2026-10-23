package com.iaf.console.search.index.adapters.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.iaf.console.fake.PlatformFake;
import com.iaf.console.platform.client.PlatformClient;
import com.iaf.console.search.index.domain.ResourceTypeHints;

/** The types the fake platform publishes, read as hints: what is searchable, what is sensitive. */
class PlatformResourceTypesTest {

    private static PlatformFake fake;

    @BeforeAll
    static void start() {
        fake = PlatformFake.builder().demo(false).build();
        fake.start(0);
    }

    @AfterAll
    static void stop() {
        fake.close();
    }

    @Test
    void theOverlaysHintsBecomeTheIndexsRules() {
        PlatformResourceTypes types = new PlatformResourceTypes(
                PlatformClient.builder().baseUrl(fake.baseUrl()).devUser("console-search-indexer").build(), Duration.ofMinutes(1));
        ResourceTypeHints vm = types.find("vm").block();
        assertThat(vm).isNotNull();
        assertThat(vm.service()).isEqualTo("compute");
        assertThat(vm.searchableFields()).contains("name", "environment");
        assertThat(vm.indexes("environment")).isTrue();
        assertThat(vm.indexes("cpu")).isFalse();
        ResourceTypeHints db = types.find("database").block();
        assertThat(db.sensitiveFields()).contains("secret");
        assertThat(db.indexes("secret")).isFalse();
        assertThat(types.find("no-such-type").blockOptional()).isEmpty();
        assertThat(types.list().collectList().block()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void anUnreachablePlatformLeavesEveryTypeUnknown() {
        PlatformResourceTypes types = new PlatformResourceTypes(
                PlatformClient.builder().baseUrl("http://127.0.0.1:9").devUser("x").responseTimeout(Duration.ofSeconds(2)).build(), Duration.ofMinutes(1));
        assertThat(types.find("vm").blockOptional()).isEmpty();
    }
}
