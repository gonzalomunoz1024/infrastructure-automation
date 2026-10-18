package com.iaf.platform.workflows.domain;

import java.time.Instant;

/**
 * One stored version of a workflow: the file as written plus what the store knows about it.
 * {@code yaml} is canonical (see {@link WorkflowYaml#toYaml}), so {@code contentHash} is stable.
 */
public record WorkflowRecord(
        String name,
        String version,
        WorkflowStatus status,
        String yaml,
        WorkflowDefinition definition,
        String contentHash,
        String path,
        Instant updatedAt,
        String updatedBy,
        Instant publishedAt,
        String publishedBy,
        String releaseId,
        String notes) {

    public static WorkflowRecord of(WorkflowDefinition d, WorkflowStatus status, String path, Instant at, String by) {
        String yaml = WorkflowYaml.toYaml(d);
        return new WorkflowRecord(d.name(), d.version(), status, yaml, d, WorkflowYaml.sha256(yaml), path, at, by, null, null, null, null);
    }

    public WorkflowRecord withStatus(WorkflowStatus s) {
        return new WorkflowRecord(name, version, s, yaml, definition, contentHash, path, updatedAt, updatedBy, publishedAt, publishedBy, releaseId, notes);
    }

    public WorkflowRecord published(Instant at, String by, String release) {
        return new WorkflowRecord(name, version, WorkflowStatus.PUBLISHED, yaml, definition, contentHash, path, at, by, at, by, release, notes);
    }

    public WorkflowRecord withDefinition(WorkflowDefinition d, Instant at, String by) {
        String y = WorkflowYaml.toYaml(d);
        return new WorkflowRecord(d.name(), d.version(), status, y, d, WorkflowYaml.sha256(y), path, at, by, publishedAt, publishedBy, releaseId, notes);
    }

    public WorkflowRecord withNotes(String n) {
        return new WorkflowRecord(name, version, status, yaml, definition, contentHash, path, updatedAt, updatedBy, publishedAt, publishedBy, releaseId, n);
    }

    public boolean isPublished() {
        return status == WorkflowStatus.PUBLISHED;
    }

    /** A version a run step or a start can resolve to: published or retired. */
    public boolean isRunnable() {
        return status == WorkflowStatus.PUBLISHED || status == WorkflowStatus.RETIRED;
    }
}
