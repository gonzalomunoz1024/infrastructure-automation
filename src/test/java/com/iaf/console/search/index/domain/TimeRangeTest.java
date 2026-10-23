package com.iaf.console.search.index.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.iaf.console.kit.errors.InvalidInputException;

/** The created: and updated: values: days, times and ages, compared or as a range, and what is refused. */
class TimeRangeTest {

    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");
    private static final Instant DAY = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant NEXT_DAY = Instant.parse("2026-10-02T00:00:00Z");

    private static TimeRange parse(String value) {
        return TimeRange.parse(value, NOW);
    }

    @Test
    void aDayIsTheWholeUtcDay() {
        assertThat(parse("2026-10-01")).isEqualTo(new TimeRange(DAY, NEXT_DAY));
        assertThat(parse(">=2026-10-01")).isEqualTo(new TimeRange(DAY, null));
        assertThat(parse(">2026-10-01")).as("after the day: from the next one").isEqualTo(new TimeRange(NEXT_DAY, null));
        assertThat(parse("<2026-10-01")).isEqualTo(new TimeRange(null, DAY));
        assertThat(parse("<=2026-10-01")).as("up to the end of the day").isEqualTo(new TimeRange(null, NEXT_DAY));
        assertThat(parse("2026-10-01..2026-10-07")).as("both ends kept").isEqualTo(new TimeRange(DAY, Instant.parse("2026-10-08T00:00:00Z")));
    }

    @Test
    void anAgeCountsBackFromNow() {
        assertThat(parse("7d")).as("an age alone is since then").isEqualTo(new TimeRange(NOW.minus(Duration.ofDays(7)), null));
        assertThat(parse("30m").from()).isEqualTo(NOW.minus(Duration.ofMinutes(30)));
        assertThat(parse("24h").from()).isEqualTo(NOW.minus(Duration.ofHours(24)));
        assertThat(parse("2w").from()).isEqualTo(NOW.minus(Duration.ofDays(14)));
        assertThat(parse("<30d")).as("older than thirty days").isEqualTo(new TimeRange(null, NOW.minus(Duration.ofDays(30))));
        assertThat(parse("30d..7d")).isEqualTo(new TimeRange(NOW.minus(Duration.ofDays(30)), NOW.minus(Duration.ofDays(7))));
    }

    @Test
    void aTimeIsExactToTheMillisecond() {
        Instant t = Instant.parse("2026-10-01T12:00:00Z");
        assertThat(parse(">=2026-10-01T12:00Z")).isEqualTo(new TimeRange(t, null));
        assertThat(parse(">2026-10-01T12:00:00Z").from()).isEqualTo(t.plusMillis(1));
        assertThat(parse("<2026-10-01T14:00+02:00")).as("an offset is honoured").isEqualTo(new TimeRange(null, t));
    }

    @Test
    void containsAndIntersects() {
        TimeRange week = parse("7d");
        assertThat(week.contains(NOW.minus(Duration.ofDays(1)))).isTrue();
        assertThat(week.contains(NOW.minus(Duration.ofDays(8)))).isFalse();
        assertThat(week.contains(null)).as("a resource without the time is outside a bounded range").isFalse();
        assertThat(TimeRange.ALL.contains(null)).isTrue();
        assertThat(parse("<2026-10-01").contains(DAY)).as("the upper end is exclusive").isFalse();
        assertThat(parse(">=2026-09-01").and(parse("<2026-10-01"))).isEqualTo(new TimeRange(Instant.parse("2026-09-01T00:00:00Z"), DAY));
        assertThat(parse("7d").and(TimeRange.ALL)).isEqualTo(parse("7d"));
    }

    @Test
    void whatIsRefused() {
        assertThatThrownBy(() -> parse("2026-10-01T12:00Z")).as("a time alone has no span").hasMessageContaining("needs >");
        assertThatThrownBy(() -> parse("last-tuesday")).hasMessageContaining("an age (30m, 24h, 7d, 2w)");
        assertThatThrownBy(() -> parse("2026-13-01")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parse("7y")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parse("..7d")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theQueryTermsNarrowAndIntersect() {
        SearchQuery q = QueryParser.parse("web created:>=2026-10-01 created:<2026-10-08 updated:24h", new QueryParser.Filters(null, null, null, Map.of(),
                null, null, null), NOW);
        assertThat(q.terms()).containsExactly("web");
        assertThat(q.created()).isEqualTo(new TimeRange(DAY, Instant.parse("2026-10-08T00:00:00Z")));
        assertThat(q.updated()).isEqualTo(new TimeRange(NOW.minus(Duration.ofHours(24)), null));
        assertThat(QueryParser.parse("web", null, NOW).created().unbounded()).isTrue();
        assertThatThrownBy(() -> QueryParser.parse("created:soon", null, NOW)).isInstanceOfSatisfying(InvalidInputException.class,
                e -> assertThat(e.problems()).anyMatch(p -> "/q".equals(p.field()) && p.message().contains("in 'created:soon'")));
    }
}
