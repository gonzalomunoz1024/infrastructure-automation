package com.iaf.console.drift.monitoring.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

/** getResourceMetrics' defaults (the last day; five minutes for a day or less, else an hour) and this service's bounds. */
class MetricsWindowTest {

    private static final Instant NOW = Instant.parse("2026-10-23T12:34:56Z");
    private static final Duration MAX_RANGE = Duration.ofDays(92);
    private static final int MAX_STEPS = 5000;

    private static MetricsWindow of(String from, String to, String step) {
        return MetricsWindow.of(from, to, step, NOW, MAX_RANGE, MAX_STEPS);
    }

    @Test
    void withNothingGivenTheLastDayInFiveMinuteSteps() {
        MetricsWindow w = of(null, null, null);
        assertThat(w.to()).isEqualTo(NOW);
        assertThat(w.from()).isEqualTo(NOW.minus(Duration.ofHours(24)));
        assertThat(w.step()).isEqualTo(Duration.ofMinutes(5));
        assertThat(w.stepText()).isEqualTo("PT5M");
        assertThat(of(" ", "", " ").step()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void theDefaultStepFollowsTheRange() {
        assertThat(of("2026-10-22T12:34:56Z", null, null).step()).as("exactly a day").isEqualTo(Duration.ofMinutes(5));
        assertThat(of("2026-10-22T12:34:55Z", null, null).step()).as("a second more").isEqualTo(Duration.ofHours(1));
        assertThat(of("2026-10-01T00:00:00Z", "2026-10-08T00:00:00Z", null).step()).isEqualTo(Duration.ofHours(1));
        // without from, the day before the given to
        MetricsWindow before = of(null, "2026-10-20T00:00:00Z", null);
        assertThat(before.from()).isEqualTo(Instant.parse("2026-10-19T00:00:00Z"));
    }

    @Test
    void anOffsetDateTimeAndAnIsoDurationAreRead() {
        MetricsWindow w = of("2026-10-20T02:00:00+02:00", "2026-10-20T06:00:00Z", "PT30M");
        assertThat(w.from()).isEqualTo(Instant.parse("2026-10-20T00:00:00Z"));
        assertThat(w.step()).isEqualTo(Duration.ofMinutes(30));
        assertThat(of(null, null, "PT1M").step()).isEqualTo(Duration.ofMinutes(1));
        assertThat(of(null, null, "P1D").stepText()).isEqualTo("PT24H");
    }

    @Test
    void badInputIsAnIllegalArgument() {
        assertThatThrownBy(() -> of(null, null, "PT59S")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("PT1M");
        assertThatThrownBy(() -> of(null, null, "5m")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ISO-8601");
        assertThatThrownBy(() -> of(null, null, "-PT5M")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> of("yesterday", null, null)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("from");
        assertThatThrownBy(() -> of(null, "2026-13-01T00:00:00Z", null)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("to");
        assertThatThrownBy(() -> of("2026-10-23T12:00:00Z", "2026-10-23T12:00:00Z", null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("before");
        assertThatThrownBy(() -> of("2026-10-23T13:00:00Z", "2026-10-23T12:00:00Z", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oneReadReachesNoFurtherThanItsBounds() {
        assertThat(of("2026-07-23T12:34:56Z", null, null).step()).as("92 days").isEqualTo(Duration.ofHours(1));
        assertThatThrownBy(() -> of("2026-07-22T12:34:56Z", null, null)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("92 days");
        // 30 days by the minute is 43200 steps
        assertThatThrownBy(() -> of("2026-09-23T12:34:56Z", null, "PT1M")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("larger step");
        assertThat(of("2026-10-20T00:00:00Z", "2026-10-23T11:20:00Z", "PT1M").step()).as("exactly 5000 steps").isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void aWindowItselfKeepsItsRules() {
        assertThatThrownBy(() -> new MetricsWindow(NOW, NOW, Duration.ofMinutes(5))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MetricsWindow(NOW.minusSeconds(60), NOW, Duration.ofSeconds(30))).isInstanceOf(IllegalArgumentException.class);
    }
}
