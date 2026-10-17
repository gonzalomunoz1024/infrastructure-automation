package com.iaf.platform.workflows.ports.outbound;

import java.util.List;
import java.util.Optional;

import com.iaf.platform.workflows.domain.WorkflowRecord;

/**
 * Where workflow files live: Git (one file per workflow, a branch per change request) or memory
 * seeded from the content dir. Every version is a record; the store is the source of truth for
 * what is published.
 */
public interface WorkflowStorePort {

    /** Every version of every workflow. */
    List<WorkflowRecord> all();

    /** Every version of one workflow, oldest first. */
    List<WorkflowRecord> versions(String name);

    Optional<WorkflowRecord> get(String name, String version);

    /** The published version with the highest semantic version. */
    Optional<WorkflowRecord> published(String name);

    Optional<WorkflowRecord> draft(String name);

    /** Upsert by name and version. */
    void save(WorkflowRecord record);

    void delete(String name, String version);
}
