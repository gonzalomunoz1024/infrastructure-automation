package com.iaf.console.state.inventory.adapters.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.state.inventory.domain.Commands;
import com.iaf.console.state.auth.domain.ConsolePrincipal;
import com.iaf.console.state.inventory.domain.DriftField;
import com.iaf.console.state.inventory.domain.StackRef;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.Problem;

/** The request bodies of the contract, and the commands they become. */
public final class Requests {

    private Requests() {
    }

    /**
     * A body that may name the person a service acts for. Only an internal caller's {@code actor} is
     * recorded; a person is always the token's subject ({@link ConsolePrincipal#actor(String)}).
     */
    public interface Acting {
        String actor();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MintRequest(String type, String region, String name, String workflow, ObjectNode input, String actor, StackRefBody stack) implements Acting {
        Commands.Mint command(String actingAs, String requestId) {
            return new Commands.Mint(type, region, name, workflow, input, new Commands.Meta(null, actingAs, requestId),
                    stack == null ? null : stack.ref());
        }
    }

    /** The stack step a mint is for (hub decision 33). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StackRefBody(String wfrn, String step, Integer attempt) {
        StackRef ref() {
            if (wfrn == null || wfrn.isBlank() || step == null || step.isBlank()) {
                throw new InvalidInputException("a stack step names the stack's wfrn and the step",
                        List.of(Problem.error("required", wfrn == null || wfrn.isBlank() ? "/stack/wfrn" : "/stack/step", "required")));
            }
            try {
                com.iaf.console.wfrn.Wfrn.parse(wfrn);
            } catch (IllegalArgumentException e) {
                throw new InvalidInputException("not a wfrn", List.of(Problem.error("wfrn", "/stack/wfrn", e.getMessage())));
            }
            return new StackRef(wfrn, step, attempt);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ImportRequest(String type, String region, String wfrn, String registryId, String providerId, String name, ObjectNode attributes,
            String source, String actor, List<String> builtFrom) implements Acting {
        Commands.Import command(String actingAs, String requestId) {
            return new Commands.Import(type, region, wfrn, registryId, providerId, name, attributes, source,
                    new Commands.Meta(null, actingAs, requestId), builtFrom);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TransferRequest(Long expectedVersion, String actor, String toAccountId, String reason) implements Acting {
        Commands.Transfer command(String actingAs, String requestId) {
            return new Commands.Transfer(toAccountId, reason, meta(expectedVersion, actingAs, requestId));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AccountTransferRequest(String toAccountId, String region, String reason, String actor, Integer limit) implements Acting {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UpdateRequest(Long expectedVersion, String actor, String jobId, String workflow, String workflowVersion, ObjectNode changes) implements Acting {
        Commands.RequestUpdate command(String actingAs, String requestId) {
            return new Commands.RequestUpdate(jobId, workflow, workflowVersion, changes, meta(expectedVersion, actingAs, requestId));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DeleteRequest(Long expectedVersion, String actor, String jobId, String workflow, String workflowVersion, String reason) implements Acting {
        Commands.RequestDelete command(String actingAs, String requestId) {
            return new Commands.RequestDelete(jobId, workflow, workflowVersion, reason, meta(expectedVersion, actingAs, requestId));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RetryRequest(Long expectedVersion, String actor, String jobId, String workflow, String workflowVersion) implements Acting {
        /** A retry with no body: the create workflow, no job yet. */
        static final RetryRequest NONE = new RetryRequest(null, null, null, null, null);

        Commands.RequestRetry command(String actingAs, String requestId) {
            return new Commands.RequestRetry(jobId, workflow, workflowVersion, meta(expectedVersion, actingAs, requestId));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TagRequest(Long expectedVersion, String actor, Map<String, String> set, List<String> removed) implements Acting {
        Commands.Tag command(String actingAs, String requestId) {
            return new Commands.Tag(set, removed, meta(expectedVersion, actingAs, requestId));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ObservationRequest(Long expectedVersion, String actor, String status, ObjectNode observed, Instant observedAt, String provider,
            String action) implements Acting {
        Commands.RecordObservation command(String actingAs, String requestId) {
            return new Commands.RecordObservation(status, observed, observedAt, provider, action, meta(expectedVersion, actingAs, requestId));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DriftRequest(Long expectedVersion, String actor, String findingId, String severity, List<DriftField> fields) implements Acting {
        Commands.RecordDrift command(String actingAs, String requestId) {
            return new Commands.RecordDrift(findingId, severity, fields, meta(expectedVersion, actingAs, requestId));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResolveDriftRequest(Long expectedVersion, String actor, String findingId, String resolution) implements Acting {
        Commands.ResolveDrift command(String actingAs, String requestId) {
            return new Commands.ResolveDrift(findingId, resolution, meta(expectedVersion, actingAs, requestId));
        }
    }

    /** {@code owner} is the team whose resources the platform's backfill names (P-05); without it, no backfill runs. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ImportAccountRequest(String accountId, String region, String owner) {
    }

    private static Commands.Meta meta(Long expectedVersion, String actor, String requestId) {
        return new Commands.Meta(expectedVersion, actor, requestId);
    }
}
