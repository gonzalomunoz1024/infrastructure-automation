package com.iaf.platform.workflows.domain;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.iaf.platform.common.domain.Hashes;

/**
 * One stored version of a workflow: the file as written plus what the store knows about it.
 * {@code yaml} is canonical (see {@link WorkflowYaml#toYaml}), so {@code contentHash} is stable.
 * {@code rolledBack} marks a retired version a release rollback took out: it stays readable,
 * and nothing resolves to it any more (stored in the record, so the stored document keeps it).
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
        String notes,
        boolean rolledBack) {

    @JsonCreator
    public WorkflowRecord {
    }

    /** A version no rollback took out. */
    public WorkflowRecord(String name, String version, WorkflowStatus status, String yaml, WorkflowDefinition definition, String contentHash, String path,
            Instant updatedAt, String updatedBy, Instant publishedAt, String publishedBy, String releaseId, String notes) {
        this(name, version, status, yaml, definition, contentHash, path, updatedAt, updatedBy, publishedAt, publishedBy, releaseId, notes, false);
    }

    public static WorkflowRecord of(WorkflowDefinition d, WorkflowStatus status, String path, Instant at, String by) {
        String yaml = WorkflowYaml.toYaml(d);
        return new WorkflowRecord(d.name(), d.version(), status, yaml, d, Hashes.sha256(yaml), path, at, by, null, null, null, null);
    }

    /** The version with another status; published again, it is no longer rolled back. */
    public WorkflowRecord withStatus(WorkflowStatus s) {
        return new WorkflowRecord(name, version, s, yaml, definition, contentHash, path, updatedAt, updatedBy, publishedAt, publishedBy, releaseId, notes,
                rolledBack && s != WorkflowStatus.PUBLISHED);
    }

    public WorkflowRecord published(Instant at, String by, String release) {
        return new WorkflowRecord(name, version, WorkflowStatus.PUBLISHED, yaml, definition, contentHash, path, at, by, at, by, release, notes, false);
    }

    /** The version a release rollback took out: retired, and never resolved again. */
    public WorkflowRecord asRolledBack() {
        return new WorkflowRecord(name, version, WorkflowStatus.RETIRED, yaml, definition, contentHash, path, updatedAt, updatedBy, publishedAt, publishedBy,
                releaseId, notes, true);
    }

    public WorkflowRecord withDefinition(WorkflowDefinition d, Instant at, String by) {
        String y = WorkflowYaml.toYaml(d);
        return new WorkflowRecord(d.name(), d.version(), status, y, d, Hashes.sha256(y), path, at, by, publishedAt, publishedBy, releaseId, notes,
                rolledBack);
    }

    public WorkflowRecord withNotes(String n) {
        return new WorkflowRecord(name, version, status, yaml, definition, contentHash, path, updatedAt, updatedBy, publishedAt, publishedBy, releaseId, n,
                rolledBack);
    }

    public boolean isPublished() {
        return status == WorkflowStatus.PUBLISHED;
    }

    /** A version a run step or a start can resolve to: published or retired, and not taken out by a rollback. */
    public boolean isRunnable() {
        return (status == WorkflowStatus.PUBLISHED || status == WorkflowStatus.RETIRED) && !rolledBack;
    }
}
