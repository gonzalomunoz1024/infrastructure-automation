package com.iaf.platform.workflows.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/** The life of one version of a workflow file. */
public enum WorkflowStatus {
    /** Being edited; not callable. */
    DRAFT,
    /** On a change-request branch, checks passed, waiting for the merge. */
    CANDIDATE,
    /** Merged: generated endpoints and MCP tool exist; a release may still be routing traffic. */
    PUBLISHED,
    /** Replaced by a newer published version; pinned jobs may still run it. */
    RETIRED;

    @JsonValue
    public String json() {
        return name().toLowerCase();
    }
}
