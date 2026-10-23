package com.iaf.console.kit.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class EventKeysTest {

    @Test
    void keysAreDeterministic() {
        assertThat(EventKeys.job("job.step", "job-1", 3)).isEqualTo("job.step:job-1:3");
        Instant at = Instant.parse("2026-10-23T10:00:00.123456789Z");
        assertThat(EventKeys.resource("resource.updated", "r-1", at)).isEqualTo("resource.updated:r-1:" + EventKeys.nanos(at));
        assertThat(EventKeys.nanos(at)).isEqualTo(1_792_749_600_123_456_789L);
        assertThat(EventKeys.content("resource-type.updated", "vm", "1.2.0")).isEqualTo("resource-type.updated:vm:1.2.0");
        assertThat(EventKeys.resource("resource.updated", "r-1", at)).isEqualTo(EventKeys.resource("resource.updated", "r-1", at));
    }

    @Test
    void aKeyNeedsItsThreeParts() {
        assertThatThrownBy(() -> EventKeys.of("", "a", "b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EventKeys.of("t", null, "b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EventKeys.of("t", "a", " ")).isInstanceOf(IllegalArgumentException.class);
    }
}
