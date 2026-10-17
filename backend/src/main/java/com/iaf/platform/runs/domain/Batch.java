package com.iaf.platform.runs.domain;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.workflows.domain.WorkflowDefinition.RolloutPolicy;

import lombok.Builder;
import lombok.With;

/** A bulk run: one child job per row, released in waves. */
@Builder(toBuilder = true)
@With
public record Batch(List<BatchRow> rows, List<Wave> waves, RolloutPolicy rollout, String stoppedReason) {

    /** One row of a batch. {@code status} is pending, running, succeeded, failed, cancelled or skipped; {@code wave} is the wave it runs in. */
    @With
    public record BatchRow(int index, JsonNode input, String jobId, String status, String error, Integer wave) {
    }

    /** One wave: {@code percent} is the cumulative share of rows reached after it; {@code status} is pending, running, done, stopped or skipped. */
    @With
    public record Wave(int index, int percent, int size, String status, Instant startedAt, Instant endedAt) {
    }

    public int total() {
        return rows == null ? 0 : rows.size();
    }

    public long count(String status) {
        return rows == null ? 0 : rows.stream().filter(r -> status.equals(r.status())).count();
    }

    /** The rows assigned to a wave. */
    public List<BatchRow> rowsOf(int wave) {
        return rows == null ? List.of() : rows.stream().filter(r -> r.wave() != null && r.wave() == wave).toList();
    }

    public Wave wave(int index) {
        return waves == null ? null : waves.stream().filter(w -> w.index() == index).findFirst().orElse(null);
    }

    public long done() {
        return rows == null ? 0 : rows.stream().filter(r -> "succeeded".equals(r.status()) || "failed".equals(r.status())
                || "cancelled".equals(r.status()) || "skipped".equals(r.status())).count();
    }
}
