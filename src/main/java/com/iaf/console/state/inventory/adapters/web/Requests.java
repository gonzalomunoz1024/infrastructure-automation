package com.iaf.console.state.inventory.adapters.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.state.inventory.domain.Commands;
import com.iaf.console.state.inventory.domain.DriftField;

/** The request bodies of the contract, and the commands they become. */
public final class Requests {

    private Requests() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MintRequest(String type, String region, String name, String workflow, ObjectNode input, String actor) {
        Commands.Mint command(String fallbackActor, String requestId) {
            return new Commands.Mint(type, region, name, workflow, input, new Commands.Meta(null, actor != null ? actor : fallbackActor, requestId));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ImportRequest(String type, String region, String wfrn, String registryId, String providerId, String name, ObjectNode attributes,
            String source, String actor) {
        Commands.Import command(String fallbackActor, String requestId) {
            return new Commands.Import(type, region, wfrn, registryId, providerId, name, attributes, source,
                    new Commands.Meta(null, actor != null ? actor : fallbackActor, requestId));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UpdateRequest(Long expectedVersion, String actor, String jobId, String workflow, String workflowVersion, ObjectNode changes) {
        Commands.RequestUpdate command(String fallbackActor, String requestId) {
            return new Commands.RequestUpdate(jobId, workflow, workflowVersion, changes, meta(expectedVersion, actor, fallbackActor, requestId));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DeleteRequest(Long expectedVersion, String actor, String jobId, String workflow, String workflowVersion, String reason) {
        Commands.RequestDelete command(String fallbackActor, String requestId) {
            return new Commands.RequestDelete(jobId, workflow, workflowVersion, reason, meta(expectedVersion, actor, fallbackActor, requestId));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TagRequest(Long expectedVersion, String actor, Map<String, String> set, List<String> removed) {
        Commands.Tag command(String fallbackActor, String requestId) {
            return new Commands.Tag(set, removed, meta(expectedVersion, actor, fallbackActor, requestId));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ObservationRequest(Long expectedVersion, String actor, String status, ObjectNode observed, Instant observedAt, String provider,
            String action) {
        Commands.RecordObservation command(String fallbackActor, String requestId) {
            return new Commands.RecordObservation(status, observed, observedAt, provider, action, meta(expectedVersion, actor, fallbackActor, requestId));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DriftRequest(Long expectedVersion, String actor, String findingId, String severity, List<DriftField> fields) {
        Commands.RecordDrift command(String fallbackActor, String requestId) {
            return new Commands.RecordDrift(findingId, severity, fields, meta(expectedVersion, actor, fallbackActor, requestId));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResolveDriftRequest(Long expectedVersion, String actor, String findingId, String resolution) {
        Commands.ResolveDrift command(String fallbackActor, String requestId) {
            return new Commands.ResolveDrift(findingId, resolution, meta(expectedVersion, actor, fallbackActor, requestId));
        }
    }

    /** {@code owner} is the team whose resources the platform's backfill names (P-05); without it, no backfill runs. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ImportAccountRequest(String accountId, String region, String owner) {
    }

    private static Commands.Meta meta(Long expectedVersion, String actor, String fallbackActor, String requestId) {
        return new Commands.Meta(expectedVersion, actor != null ? actor : fallbackActor, requestId);
    }
}
