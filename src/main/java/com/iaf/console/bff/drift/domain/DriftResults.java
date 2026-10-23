package com.iaf.console.bff.drift.domain;

/** What the drift service's writes answer, in the shapes the BFF passes on. */
public final class DriftResults {

    private DriftResults() {
    }

    /** A revert's job was started; the finding stays open with {@code revertStatus: running} until it ends. */
    public record RevertStarted(String findingId, String jobId) {
    }

    /** An apply's job was started; the recommendation is {@code applied} from now on. */
    public record ApplyStarted(String recommendationId, String jobId) {
    }

    /** A discovered object is managed now, under this name. */
    public record Imported(String wfrn) {
    }

    /** An alarm after a PUT, and whether the PUT created it (201) or replaced it (200). */
    public record AlarmSaved(Alarm alarm, boolean created) {
    }
}
