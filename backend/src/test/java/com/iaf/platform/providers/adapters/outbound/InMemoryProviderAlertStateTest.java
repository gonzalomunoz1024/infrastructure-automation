package com.iaf.platform.providers.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class InMemoryProviderAlertStateTest {

    static final Instant T0 = Instant.parse("2026-10-17T10:00:00.123456789Z");

    @Test
    void theDemoKeepsItInMemoryAndOnlyForward() {
        InMemoryProviderAlertState state = new InMemoryProviderAlertState();
        assertThat(state.lastEvaluatedAt()).isEmpty();
        state.lastEvaluatedAt(T0);
        state.lastEvaluatedAt(T0.minusSeconds(1));
        state.lastEvaluatedAt(null);
        assertThat(state.lastEvaluatedAt()).contains(T0);
    }
}
