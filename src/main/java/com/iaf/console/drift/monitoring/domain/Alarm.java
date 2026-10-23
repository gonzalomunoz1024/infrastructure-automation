package com.iaf.console.drift.monitoring.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * An alarm on one series of one resource, named by the caller ({@code alarmId}, unique in the
 * account, so a retried create is the same alarm). Every minute it is evaluated: the average of the
 * series over its window is compared with the threshold. {@code stateSince} moves only when the
 * state changes, so it says how long the alarm has been firing. {@code rev} is the stored revision:
 * an evaluation that read the alarm before a change never undoes that change.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Alarm(
        String alarmId,
        String wfrn,
        String accountId,
        String type,
        String name,
        String metric,
        Comparison comparison,
        double threshold,
        int windowMinutes,
        AlarmState state,
        Instant stateSince,
        Double lastValue,
        Instant lastEvaluatedAt,
        String createdBy,
        Instant createdAt,
        Instant updatedAt,
        @JsonIgnore long rev) {

    /** A new alarm: {@code insufficient} until its first evaluation has points to average. */
    public static Alarm created(String alarmId, String accountId, String type, String wfrn, String name, String metric, Comparison comparison,
            double threshold, int windowMinutes, String by, Instant now) {
        return new Alarm(alarmId, wfrn, accountId, type, name, metric, comparison, threshold, windowMinutes, AlarmState.INSUFFICIENT, now, null, null,
                by, now, now, 0);
    }

    /** The same alarm with another definition; its state stays until the next evaluation (putAlarm). */
    public Alarm changed(String newType, String newWfrn, String newName, String newMetric, Comparison newComparison, double newThreshold,
            int newWindowMinutes, Instant now) {
        return new Alarm(alarmId, newWfrn, accountId, newType, newName, newMetric, newComparison, newThreshold, newWindowMinutes, state, stateSince,
                lastValue, lastEvaluatedAt, createdBy, createdAt, now, rev);
    }

    /**
     * The alarm after an evaluation at {@code now} over its series' points: the average of the values
     * in the window (after {@code now} less the window, up to {@code now}) against the threshold, and
     * {@code insufficient} when the window holds no value. Called with no points when the series is
     * missing altogether.
     */
    public Alarm evaluated(List<MetricPoint> points, Instant now) {
        Instant start = now.minus(Duration.ofMinutes(windowMinutes));
        double sum = 0;
        int n = 0;
        for (MetricPoint p : points) {
            if (p.at() != null && p.at().isAfter(start) && !p.at().isAfter(now) && p.value() != null && Double.isFinite(p.value())) {
                sum += p.value();
                n++;
            }
        }
        Double average = n == 0 ? null : sum / n;
        AlarmState next = average == null ? AlarmState.INSUFFICIENT : comparison.crossed(average, threshold) ? AlarmState.ALARM : AlarmState.OK;
        Instant since = next == state && stateSince != null ? stateSince : now;
        return new Alarm(alarmId, wfrn, accountId, type, name, metric, comparison, threshold, windowMinutes, next, since,
                average == null ? null : Math.round(average * 1000.0) / 1000.0, now, createdBy, createdAt, updatedAt, rev);
    }

    /** The same alarm at another stored revision: what a store answers after a write. */
    public Alarm withRev(long newRev) {
        return new Alarm(alarmId, wfrn, accountId, type, name, metric, comparison, threshold, windowMinutes, state, stateSince, lastValue,
                lastEvaluatedAt, createdBy, createdAt, updatedAt, newRev);
    }
}
