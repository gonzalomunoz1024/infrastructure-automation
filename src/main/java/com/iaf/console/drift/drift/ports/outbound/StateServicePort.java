package com.iaf.console.drift.drift.ports.outbound;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.drift.drift.domain.FindingField;
import com.iaf.console.drift.drift.domain.Observation;
import com.iaf.console.drift.drift.domain.Resolution;
import com.iaf.console.drift.drift.domain.Severity;
import com.iaf.console.drift.drift.domain.StateResource;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Mono;

/**
 * The state service, called as this service's own principal ({@code svc console-drift-service}),
 * which may name any account: the inventory this service watches, and the commands that record
 * what the providers report and what the account decided.
 */
@GlobalRead("called as the console-drift-service principal; the account travels in the wfrn or the query")
public interface StateServicePort {

    /** An account's resources changed after a time, oldest change first. */
    Mono<Page<StateResource>> listResources(String accountId, Instant updatedAfter, String after, int limit);

    Mono<StateResource> getResource(String wfrn);

    Mono<StateResource> recordObservation(String wfrn, Observation observation, String actor);

    Mono<StateResource> recordDrift(String wfrn, String findingId, Severity severity, List<FindingField> fields, String actor);

    Mono<StateResource> resolveDrift(String wfrn, String findingId, Resolution resolution, String actor);

    Mono<StateResource> requestUpdate(String wfrn, String workflow, String workflowVersion, ObjectNode changes, String actor, String requestId);

    /** Takes a discovered object under management ({@code POST /v1/resources:import}, source {@code discover}). */
    Mono<StateResource> importResource(ImportCommand command);

    /**
     * Records a delete request ({@code :request-delete}): the resource is {@code deleting} until the
     * delete workflow's job ends. Applying a rightsizing recommendation to delete an idle resource
     * calls it before the job starts, as a revert calls {@link #requestUpdate}. A default, so a
     * stand-in written before it still compiles; both adapters answer it.
     */
    default Mono<StateResource> requestDelete(String wfrn, String workflow, String reason, String actor, String requestId) {
        return Mono.error(new StateError(501, "state.notImplemented", null));
    }

    /**
     * Tells the state which job carries out the update (or delete) just requested. The state service learns it
     * from the platform's feed on its own, so the HTTP adapter does nothing; the demo's embedded
     * stand-in has no feed and follows the job from here.
     */
    default Mono<Void> noteJob(String wfrn, String jobId) {
        return Mono.empty();
    }

    /** What an import carries: the name this service minted, the provider's id and what it reported. */
    record ImportCommand(String type, String region, String wfrn, String providerId, String name, ObjectNode attributes, String actor,
            String requestId) {
    }

    /** The state service refused or failed: its status, its {@code error} and its {@code detail}. */
    final class StateError extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int status;
        private final String error;
        private final transient com.fasterxml.jackson.databind.JsonNode detail;

        public StateError(int status, String error, com.fasterxml.jackson.databind.JsonNode detail) {
            super("state service answered " + status + ": " + error);
            this.status = status;
            this.error = error;
            this.detail = detail;
        }

        public int status() {
            return status;
        }

        public String error() {
            return error;
        }

        public com.fasterxml.jackson.databind.JsonNode detail() {
            return detail;
        }

        public String detailText(String field) {
            return detail == null || !detail.hasNonNull(field) ? null : detail.get(field).asText();
        }

        public boolean notFound() {
            return status == 404;
        }

        public boolean conflict() {
            return status == 409;
        }
    }
}
