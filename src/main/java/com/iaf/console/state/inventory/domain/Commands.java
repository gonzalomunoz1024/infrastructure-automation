package com.iaf.console.state.inventory.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** The commands the inventory takes: the API's (with {@code expectedVersion}) and the platform's. */
public final class Commands {

    private Commands() {
    }

    /** What every API command carries: the version the caller saw, who acts and the console request. */
    public record Meta(Long expectedVersion, String actor, String requestId) {
        public static Meta of(String actor) {
            return new Meta(null, actor, null);
        }
    }

    /** {@code POST /v1/resources:mint}. */
    public record Mint(String type, String region, String name, String workflow, ObjectNode input, Meta meta) {
    }

    /** {@code POST /v1/resources:import}. */
    public record Import(String type, String region, String wfrn, String registryId, String providerId, String name, ObjectNode attributes,
            String source, Meta meta) {
    }

    /** {@code :request-update}. */
    public record RequestUpdate(String jobId, String workflow, String workflowVersion, ObjectNode changes, Meta meta) {
    }

    /** {@code :request-delete}. */
    public record RequestDelete(String jobId, String workflow, String workflowVersion, String reason, Meta meta) {
    }

    /** {@code :tag}. */
    public record Tag(Map<String, String> set, List<String> removed, Meta meta) {
    }

    /** {@code :record-observation}. */
    public record RecordObservation(String status, ObjectNode observed, Instant observedAt, String provider, String action, Meta meta) {
    }

    /** {@code :record-drift}. */
    public record RecordDrift(String findingId, String severity, List<DriftField> fields, Meta meta) {
    }

    /** {@code :resolve-drift}. */
    public record ResolveDrift(String findingId, String resolution, Meta meta) {
    }

    /** The platform's {@code job.started} for a resource the console requested. */
    public record JobStarted(String jobId, String workflow, String workflowVersion, Meta meta) {
    }

    /** The platform registered the resource (or re-registered it with new attributes): what the registry holds. */
    public record Registration(String jobId, String workflow, String workflowVersion, String registryId, String providerId, String name,
            ObjectNode attributes, Meta meta) {
    }

    /** The platform job ended without the resource: failed, cancelled or rolled back. */
    public record JobFailed(String jobId, String workflow, String workflowVersion, String status, String errorClass, String failedStep,
            String message, Meta meta) {
    }

    /** The platform retired the resource, the delete job succeeded, or the provider says it is gone. */
    public record Deletion(String jobId, String registryId, Meta meta) {
    }
}
