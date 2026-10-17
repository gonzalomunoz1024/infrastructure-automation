package com.iaf.platform.runs.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.runs.domain.JobStep.StepError;
import com.iaf.platform.runs.domain.JobStep.StepStatus;
import com.iaf.platform.workflows.domain.StepType;

/**
 * How step events fold into the {@link JobStep} rows of a job: one row per step id in order of
 * first appearance; a new attempt resets the row; the end of an attempt stamps its output or
 * error and its duration. The projection applies this to {@code job.step} events, the seed to
 * the events of a simulated walk.
 */
public final class StepFolding {

    private StepFolding() {
    }

    public static List<JobStep> fold(List<StepEvent> events) {
        List<JobStep> out = new ArrayList<>();
        for (StepEvent e : events) {
            apply(out, e.id(), e.name(), e.step().type(), e.status(), e.attempt(), e.input(), e.output(), e.error(), e.at(), e.durationMs(), e.items());
        }
        return out;
    }

    /** Apply one step event to a mutable list of rows. */
    public static void apply(List<JobStep> rows, String id, String name, StepType type, StepStatus status, int attempt, JsonNode input,
            JsonNode output, StepError error, Instant at, Long durationMs, Integer items) {
        int idx = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).id().equals(id)) {
                idx = i;
                break;
            }
        }
        JobStep existing = idx < 0 ? null : rows.get(idx);
        JobStep next = apply(existing, id, name, type, status, attempt, input, output, error, at, durationMs, items);
        if (idx < 0) {
            rows.add(next);
        } else {
            rows.set(idx, next);
        }
    }

    public static JobStep apply(JobStep existing, String id, String name, StepType type, StepStatus status, int attempt, JsonNode input,
            JsonNode output, StepError error, Instant at, Long durationMs, Integer items) {
        JobStep.JobStepBuilder b = existing == null
                ? JobStep.builder().id(id).name(name).type(type).attempt(Math.max(1, attempt))
                : existing.toBuilder();
        if (name != null) {
            b.name(name);
        }
        if (type != null) {
            b.type(type);
        }
        switch (status == null ? StepStatus.RUNNING : status) {
            case RUNNING -> b.status(StepStatus.RUNNING).attempt(Math.max(1, attempt)).startedAt(at).endedAt(null).durationMs(null)
                    .output(null).error(null).input(input);
            case WAITING -> {
                b.status(StepStatus.WAITING);
                if (input != null) {
                    b.input(input);
                }
                if (existing == null || existing.startedAt() == null) {
                    b.startedAt(at);
                }
            }
            case SUCCEEDED -> {
                b.status(StepStatus.SUCCEEDED).endedAt(at).output(output).error(null).items(items);
                if (attempt > 0) {
                    b.attempt(attempt);
                }
                if (input != null) {
                    b.input(input);
                }
                Instant started = existing == null ? null : existing.startedAt();
                if (started == null && at != null && durationMs != null) {
                    started = at.minusMillis(durationMs);
                }
                b.startedAt(started);
                b.durationMs(durationMs != null ? durationMs : started != null && at != null ? Math.max(0, at.toEpochMilli() - started.toEpochMilli()) : null);
            }
            case FAILED -> {
                b.status(StepStatus.FAILED).endedAt(at).error(error);
                if (attempt > 0) {
                    b.attempt(attempt);
                }
                if (input != null) {
                    b.input(input);
                }
                Instant started = existing == null ? null : existing.startedAt();
                if (started == null && at != null && durationMs != null) {
                    started = at.minusMillis(durationMs);
                }
                b.startedAt(started);
                b.durationMs(durationMs != null ? durationMs : started != null && at != null ? Math.max(0, at.toEpochMilli() - started.toEpochMilli()) : null);
            }
            case SKIPPED -> b.status(StepStatus.SKIPPED).endedAt(at);
            case COMPENSATED -> b.status(StepStatus.COMPENSATED);
            case PENDING -> b.status(StepStatus.PENDING);
        }
        return b.build();
    }
}
