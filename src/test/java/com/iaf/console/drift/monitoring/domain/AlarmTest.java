package com.iaf.console.drift.monitoring.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** An alarm's evaluation: the window's average against the threshold, no points as insufficient, and how long it has been in its state. */
class AlarmTest {

    private static final Instant T0 = Instant.parse("2026-10-23T12:00:00Z");
    private static final String WFRN = "wfrn:wf:compute:global:123456789012:vm/vm-01";

    private static Alarm alarm(Comparison comparison, double threshold, int windowMinutes) {
        return Alarm.created("cpu-high", "123456789012", "vm", WFRN, "busy", "cpuPercent", comparison, threshold, windowMinutes, "dana", T0);
    }

    /** One point a minute up to {@code now}, the newest last. */
    private static List<MetricPoint> minutes(Instant now, Double... values) {
        List<MetricPoint> points = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            points.add(new MetricPoint(now.minus(Duration.ofMinutes(values.length - 1 - i)), values[i]));
        }
        return points;
    }

    @Test
    void aNewAlarmIsInsufficientUntilItsFirstPoints() {
        Alarm a = alarm(Comparison.ABOVE, 80, 5);
        assertThat(a.state()).isEqualTo(AlarmState.INSUFFICIENT);
        assertThat(a.stateSince()).isEqualTo(T0);
        assertThat(a.createdBy()).isEqualTo("dana");
        assertThat(a.lastEvaluatedAt()).isNull();
        assertThat(a.rev()).isZero();
    }

    @Test
    void theWindowsAverageAgainstTheThresholdDecides() {
        Instant now = T0.plusSeconds(600);
        Alarm above = alarm(Comparison.ABOVE, 80, 5).evaluated(minutes(now, 70.0, 90.0, 95.0), now);
        assertThat(above.state()).isEqualTo(AlarmState.ALARM);
        assertThat(above.lastValue()).isEqualTo(85.0);
        assertThat(above.lastEvaluatedAt()).isEqualTo(now);

        Alarm ok = alarm(Comparison.ABOVE, 85, 5).evaluated(minutes(now, 70.0, 90.0, 95.0), now);
        assertThat(ok.state()).as("strictly above").isEqualTo(AlarmState.OK);

        Alarm below = alarm(Comparison.BELOW, 10, 5).evaluated(minutes(now, 4.0, 5.0, 6.5), now);
        assertThat(below.state()).isEqualTo(AlarmState.ALARM);
        assertThat(below.lastValue()).isEqualTo(5.167);
    }

    @Test
    void onlyPointsInsideTheWindowCountAndGapsAreLeftOut() {
        Instant now = T0.plusSeconds(3600);
        List<MetricPoint> points = new ArrayList<>(minutes(now, 99.0, null, 10.0, 20.0));
        points.add(0, new MetricPoint(now.minus(Duration.ofMinutes(30)), 1000.0));
        points.add(new MetricPoint(now.plusSeconds(60), 1000.0));
        points.add(new MetricPoint(now.minusSeconds(30), Double.NaN));
        // a three-minute window holds the points after now - 3 min: null, 10 and 20 (the 99 is on its edge)
        Alarm a = alarm(Comparison.ABOVE, 14, 3).evaluated(points, now);
        assertThat(a.lastValue()).isEqualTo(15.0);
        assertThat(a.state()).isEqualTo(AlarmState.ALARM);
    }

    @Test
    void noPointsIsInsufficientAndTheStateSinceOnlyMovesOnAChange() {
        Instant first = T0.plusSeconds(60);
        Alarm firing = alarm(Comparison.ABOVE, 50, 5).evaluated(minutes(first, 60.0), first);
        assertThat(firing.state()).isEqualTo(AlarmState.ALARM);
        assertThat(firing.stateSince()).isEqualTo(first);

        Instant second = first.plusSeconds(60);
        Alarm still = firing.evaluated(minutes(second, 70.0), second);
        assertThat(still.stateSince()).as("still firing since the first evaluation").isEqualTo(first);
        assertThat(still.lastValue()).isEqualTo(70.0);

        Instant third = second.plusSeconds(60);
        Alarm empty = still.evaluated(List.of(), third);
        assertThat(empty.state()).isEqualTo(AlarmState.INSUFFICIENT);
        assertThat(empty.lastValue()).isNull();
        assertThat(empty.stateSince()).isEqualTo(third);

        Alarm cleared = empty.evaluated(minutes(third.plusSeconds(60), 10.0), third.plusSeconds(60));
        assertThat(cleared.state()).isEqualTo(AlarmState.OK);
        assertThat(cleared.stateSince()).isEqualTo(third.plusSeconds(60));
    }

    @Test
    void aChangeKeepsTheStateUntilTheNextEvaluation() {
        Instant now = T0.plusSeconds(60);
        Alarm firing = alarm(Comparison.ABOVE, 50, 5).evaluated(minutes(now, 60.0), now).withRev(3);
        Alarm changed = firing.changed("vm", WFRN, null, "memoryPercent", Comparison.BELOW, 20, 15, now.plusSeconds(5));
        assertThat(changed.state()).isEqualTo(AlarmState.ALARM);
        assertThat(changed.stateSince()).isEqualTo(now);
        assertThat(changed.metric()).isEqualTo("memoryPercent");
        assertThat(changed.windowMinutes()).isEqualTo(15);
        assertThat(changed.name()).isNull();
        assertThat(changed.createdAt()).isEqualTo(T0);
        assertThat(changed.updatedAt()).isEqualTo(now.plusSeconds(5));
        assertThat(changed.rev()).isEqualTo(3);
    }

    @Test
    void theWordsOfTheContract() {
        assertThat(AlarmState.of("alarm")).isEqualTo(AlarmState.ALARM);
        assertThat(AlarmState.of("firing")).isNull();
        assertThat(AlarmState.ALARM.rank()).isLessThan(AlarmState.INSUFFICIENT.rank());
        assertThat(AlarmState.INSUFFICIENT.rank()).isLessThan(AlarmState.OK.rank());
        assertThat(Comparison.of("below")).isEqualTo(Comparison.BELOW);
        assertThat(Comparison.of("under")).isNull();
        assertThat(new AlarmInput(WFRN, null, "cpuPercent", "above", 1.0, null, null).windowOrDefault()).isEqualTo(10);
    }
}
