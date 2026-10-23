package com.iaf.console.state.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.iaf.console.platform.model.Resource;

/** A record found by registry id is the resource only when it is bound to no other name and no other account. */
class PlatformResourcesTest {

    private static final String WFRN = "wfrn:wf:compute:global:123456789012:vm/vm-01";

    private static Resource record(String wfrn, String accountId) {
        return new Resource("vm-1", "vm", "web-01", "ready", Map.of(), "team-compute", null, null, null, null, null, wfrn, accountId, "global", null,
                null);
    }

    @Test
    void aRegistryIdNeverPullsInAnotherAccountsRecord() {
        assertThat(PlatformResources.belongs(record(null, null), WFRN)).as("not bound yet").isTrue();
        assertThat(PlatformResources.belongs(record(WFRN, "123456789012"), WFRN)).isTrue();
        assertThat(PlatformResources.belongs(record(null, "123456789012"), WFRN)).isTrue();
        assertThat(PlatformResources.belongs(record(null, "210987654321"), WFRN)).as("another account's record").isFalse();
        assertThat(PlatformResources.belongs(record("wfrn:wf:compute:global:210987654321:vm/vm-09", "210987654321"), WFRN)).isFalse();
        assertThat(PlatformResources.belongs(record("wfrn:wf:compute:global:123456789012:vm/vm-02", "123456789012"), WFRN))
                .as("bound to another name").isFalse();
    }
}
