package com.iaf.console.state.inventory.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.Problem;

/**
 * Decides what a command does to a resource: the one event it produces (with the next version and
 * the state after it), nothing when the command changes nothing, or a refusal: 409 on a version
 * mismatch or a transition the state machine forbids, 410 on a tombstone, 400 on bad input.
 * The decider is pure: it sees the resource before the command and the clock the caller passes.
 */
public final class ResourceDecider {

    private ResourceDecider() {
    }

    // opening a resource

    public static ResourceEvent mint(ResourceIdentity id, Commands.Mint command, Instant now) {
        requireText(command.workflow(), "/workflow");
        ObjectNode data = Json.object();
        data.put("resourceType", id.type());
        data.put("service", id.service());
        Json.putIf(data, "name", command.name());
        data.put("workflow", command.workflow());
        Json.setIf(data, "input", command.input());
        return event(null, id, ResourceEventType.RESOURCE_REQUESTED, now, command.meta(), null, data);
    }

    public static ResourceEvent importResource(Resource current, ResourceIdentity id, Commands.Import command, Instant now) {
        requireText(command.registryId(), "/registryId");
        if (!"backfill".equals(command.source()) && !"discover".equals(command.source())) {
            throw new InvalidInputException("the import source is backfill or discover",
                    List.of(Problem.error("enum", "/source", "one of: backfill, discover")));
        }
        checkVersion(current, command.meta());
        ObjectNode data = Json.object();
        data.put("resourceType", id.type());
        data.put("service", id.service());
        data.put("source", command.source());
        data.put("registryId", command.registryId());
        Json.putIf(data, "providerId", command.providerId());
        Json.putIf(data, "name", command.name());
        data.set("attributes", Json.orEmpty(command.attributes()));
        lineage(data, command.builtFrom());
        return event(current, id, ResourceEventType.IMPORTED, now, command.meta(), null, data);
    }

    /**
     * The resource moves to another account: a tombstone here, kept for good, that names the wfrn
     * it becomes there. Only an idle, active resource moves.
     */
    public static ResourceEvent transfer(Resource current, Commands.Transfer command, ResourceIdentity to, Instant now) {
        requireExists(current);
        checkVersion(current, command.meta());
        ObjectNode data = Json.object();
        data.put("transferredTo", to.wfrn());
        data.put("toAccountId", to.accountId());
        Json.putIf(data, "registryId", current.registryId());
        Json.putIf(data, "reason", command.reason());
        return event(current, null, ResourceEventType.TRANSFERRED, now, command.meta(), null, data);
    }

    /**
     * The other half of a transfer: the new wfrn opens with {@code Imported}, {@code source: transfer},
     * carrying what the resource was (attributes in clear, sealed again under the new account's key
     * when committed), its tags and lineage, and the wfrn it was.
     */
    public static ResourceEvent transferredIn(ResourceIdentity id, Resource from, ObjectNode attributes, Commands.Meta meta, Instant now) {
        ObjectNode data = Json.object();
        data.put("resourceType", id.type());
        data.put("service", id.service());
        data.put("source", "transfer");
        Json.putIf(data, "registryId", from.registryId());
        Json.putIf(data, "providerId", from.providerId());
        Json.putIf(data, "name", from.name());
        data.set("attributes", Json.orEmpty(attributes));
        if (!from.tags().isEmpty()) {
            ObjectNode tags = data.putObject("tags");
            from.tags().forEach(tags::put);
        }
        lineage(data, from.builtFrom().isEmpty() ? null : from.builtFrom());
        data.put("transferredFrom", from.wfrn());
        return event(null, id, ResourceEventType.IMPORTED, now, meta, null, data);
    }

    // the API's commands

    public static ResourceEvent requestUpdate(Resource current, Commands.RequestUpdate command, Instant now) {
        requireExists(current);
        requireText(command.workflow(), "/workflow");
        if (command.changes() == null || command.changes().isEmpty()) {
            throw new InvalidInputException("an update names the fields it changes", List.of(Problem.error("required", "/changes", "required")));
        }
        checkVersion(current, command.meta());
        ObjectNode data = job(command.jobId(), command.workflow(), command.workflowVersion());
        data.set("changes", command.changes());
        return event(current, null, ResourceEventType.UPDATE_REQUESTED, now, command.meta(), command.jobId(), data);
    }

    public static ResourceEvent requestDelete(Resource current, Commands.RequestDelete command, Instant now) {
        requireExists(current);
        requireText(command.workflow(), "/workflow");
        checkVersion(current, command.meta());
        ObjectNode data = job(command.jobId(), command.workflow(), command.workflowVersion());
        Json.putIf(data, "reason", command.reason());
        return event(current, null, ResourceEventType.DELETE_REQUESTED, now, command.meta(), command.jobId(), data);
    }

    /**
     * A failed create, tried again: the resource is pending once more, and the job the BFF reruns
     * carries the retry ({@code retryOf} names the failed job). Empty when the job the retry names is
     * already on record: the BFF may rerun the failed job before it records the retry, and that job's
     * outcome may come first; it stands.
     */
    public static Optional<ResourceEvent> requestRetry(Resource current, Commands.RequestRetry command, Instant now) {
        requireExists(current);
        if (command.jobId() != null && command.jobId().equals(current.lastJobId())) {
            return Optional.empty();
        }
        Transitions.next(current.state(), ResourceEventType.RETRY_REQUESTED, current); // 409 before the input is looked at
        String workflow = command.workflow() != null && !command.workflow().isBlank() ? command.workflow() : current.workflow();
        requireText(workflow, "/workflow");
        checkVersion(current, command.meta());
        ObjectNode data = job(command.jobId(), workflow, command.workflowVersion());
        Json.putIf(data, "retryOf", current.lastError() != null && current.lastError().jobId() != null ? current.lastError().jobId()
                : current.lastJobId());
        return Optional.of(event(current, null, ResourceEventType.RETRY_REQUESTED, now, command.meta(), command.jobId(), data));
    }

    /** Empty when the tags already are what the command asks. */
    public static Optional<ResourceEvent> tag(Resource current, Commands.Tag command, Instant now) {
        requireExists(current);
        checkVersion(current, command.meta());
        Map<String, String> set = command.set() == null ? Map.of() : command.set();
        List<String> removed = command.removed() == null ? List.of() : command.removed();
        Map<String, String> result = new TreeMap<>(current.tags());
        result.putAll(set);
        removed.forEach(result::remove);
        TagRules.require(set, removed, result.size());
        if (result.equals(current.tags())) {
            return Optional.empty();
        }
        ObjectNode data = Json.object();
        ObjectNode setNode = Json.object();
        set.forEach((k, v) -> {
            if (!v.equals(current.tags().get(k))) {
                setNode.put(k, v);
            }
        });
        ArrayNode removedNode = data.arrayNode();
        for (String name : removed) {
            if (current.tags().containsKey(name) && !set.containsKey(name)) {
                removedNode.add(name);
            }
        }
        if (!setNode.isEmpty()) {
            data.set("set", setNode);
        }
        if (!removedNode.isEmpty()) {
            data.set("removed", removedNode);
        }
        return Optional.of(event(current, null, ResourceEventType.TAGGED, now, command.meta(), null, data));
    }

    /** Empty when the provider reports what it reported last time: the log grows with change, not with the clock. */
    public static Optional<ResourceEvent> recordObservation(Resource current, Commands.RecordObservation command, Instant now) {
        requireExists(current);
        if (!"found".equals(command.status()) && !"not_found".equals(command.status()) && !"error".equals(command.status())) {
            throw new InvalidInputException("an observation's status is found, not_found or error",
                    List.of(Problem.error("enum", "/status", "one of: found, not_found, error")));
        }
        if (command.observedAt() == null) {
            throw new InvalidInputException("an observation says when it was made", List.of(Problem.error("required", "/observedAt", "required")));
        }
        checkVersion(current, command.meta());
        boolean sameStatus = command.status().equals(current.observedStatus());
        boolean sameValues = "found".equals(command.status())
                ? Objects.equals(Json.orEmpty(command.observed()), Json.orEmpty(current.observed()))
                : current.observed() == null;
        if (sameStatus && sameValues && !"error".equals(command.status())) {
            return Optional.empty();
        }
        ObjectNode data = Json.object();
        data.put("status", command.status());
        if ("found".equals(command.status())) {
            data.set("observed", Json.orEmpty(command.observed()));
        }
        data.put("observedAt", command.observedAt().toString());
        Json.putIf(data, "provider", command.provider());
        Json.putIf(data, "action", command.action());
        return Optional.of(event(current, null, ResourceEventType.OBSERVED, now, command.meta(), null, data));
    }

    public static ResourceEvent recordDrift(Resource current, Commands.RecordDrift command, Instant now) {
        requireExists(current);
        requireText(command.findingId(), "/findingId");
        if (command.fields() == null || command.fields().isEmpty()) {
            throw new InvalidInputException("a finding names the fields that drifted", List.of(Problem.error("required", "/fields", "required")));
        }
        checkVersion(current, command.meta());
        if (current.drift() != null && !current.drift().findingId().equals(command.findingId())) {
            throw new ConflictException("drift.open: finding '" + current.drift().findingId() + "' is open on this resource; resolve it first",
                    Map.of("findingId", current.drift().findingId()));
        }
        ObjectNode data = Json.object();
        data.put("findingId", command.findingId());
        Json.putIf(data, "severity", command.severity());
        data.set("fields", Json.node(command.fields()));
        return event(current, null, ResourceEventType.DRIFT_DETECTED, now, command.meta(), null, data);
    }

    public static ResourceEvent resolveDrift(Resource current, Commands.ResolveDrift command, Instant now) {
        requireExists(current);
        requireText(command.findingId(), "/findingId");
        if (!List.of("reverted", "accepted", "ignored", "vanished").contains(command.resolution())) {
            throw new InvalidInputException("a resolution is reverted, accepted, ignored or vanished",
                    List.of(Problem.error("enum", "/resolution", "one of: reverted, accepted, ignored, vanished")));
        }
        checkVersion(current, command.meta());
        if (current.drift() == null) {
            throw new ConflictException("drift.none: no finding is open on this resource");
        }
        if (!current.drift().findingId().equals(command.findingId())) {
            throw new ConflictException("drift.mismatch: the open finding is '" + current.drift().findingId() + "'",
                    Map.of("findingId", current.drift().findingId()));
        }
        ObjectNode data = Json.object();
        data.put("findingId", command.findingId());
        data.put("resolution", command.resolution());
        return event(current, null, ResourceEventType.DRIFT_RESOLVED, now, command.meta(), null, data);
    }

    // the platform's facts

    /**
     * Empty for a job already recorded, for the job whose failure is on record (it cannot start a
     * retry), and for a job on a resource that waits for none (active, failed, deleted): the BFF may
     * start a job before it records the change, and the request that names the job, or the job's
     * end, says what it means.
     */
    public static Optional<ResourceEvent> jobStarted(Resource current, Commands.JobStarted command, Instant now) {
        requireExists(current);
        if (command.jobId() != null && (command.jobId().equals(current.lastJobId()) || failureOnRecord(current, command.jobId()))) {
            return Optional.empty();
        }
        if (!current.state().busy()) {
            return Optional.empty();
        }
        ObjectNode data = job(command.jobId(), command.workflow(), command.workflowVersion());
        return Optional.of(event(current, null, ResourceEventType.PROVISIONING_STARTED, now, command.meta(), command.jobId(), data));
    }

    /**
     * Empty when the registration says nothing new about an active resource, and for a resource on its
     * way out: a registration cannot end a delete, only the delete job's end can (hub decision 20).
     */
    public static Optional<ResourceEvent> registered(Resource current, Commands.Registration command, Instant now) {
        requireExists(current);
        if (current.state() == ResourceState.DELETING) {
            return Optional.empty();
        }
        ObjectNode attributes = Json.orEmpty(command.attributes());
        if (current.state() == ResourceState.ACTIVE && Objects.equals(command.registryId(), current.registryId())
                && Objects.equals(attributes, Json.orEmpty(current.attributes()))
                && (command.providerId() == null || command.providerId().equals(current.providerId()))
                && (command.builtFrom() == null || command.builtFrom().equals(current.builtFrom()))) {
            return Optional.empty();
        }
        ObjectNode data = job(command.jobId(), command.workflow(), command.workflowVersion());
        data.put("registryId", command.registryId());
        Json.putIf(data, "providerId", command.providerId());
        Json.putIf(data, "name", command.name());
        data.set("attributes", attributes);
        lineage(data, command.builtFrom());
        return Optional.of(event(current, null, ResourceEventType.PROVISIONING_SUCCEEDED, now, command.meta(), command.jobId(), data));
    }

    public static ResourceEvent jobFailed(Resource current, Commands.JobFailed command, Instant now) {
        requireExists(current);
        ObjectNode data = job(command.jobId(), command.workflow(), command.workflowVersion());
        String status = command.status() == null ? "failed" : command.status();
        data.put("status", status);
        Json.putIf(data, "errorClass", command.errorClass());
        Json.putIf(data, "failedStep", command.failedStep());
        Json.putIf(data, "message", command.message());
        return event(current, null, ResourceEventType.PROVISIONING_FAILED, now, command.meta(), command.jobId(), data);
    }

    /** Empty when the resource is already a tombstone. */
    public static Optional<ResourceEvent> deleted(Resource current, Commands.Deletion command, Instant now, Duration tombstoneTtl) {
        Objects.requireNonNull(current, "a deletion needs a resource");
        if (current.deleted()) {
            return Optional.empty();
        }
        ObjectNode data = Json.object();
        Json.putIf(data, "jobId", command.jobId());
        Json.putIf(data, "registryId", command.registryId() != null ? command.registryId() : current.registryId());
        data.put("tombstoneUntil", now.plus(tombstoneTtl).toString());
        return Optional.of(event(current, null, ResourceEventType.DELETED, now, command.meta(), command.jobId(), data));
    }

    /** Whether the job's failure is the one the resource has on record. */
    public static boolean failureOnRecord(Resource current, String jobId) {
        return jobId != null && current.lastError() != null && jobId.equals(current.lastError().jobId());
    }

    // helpers

    private static ResourceEvent event(Resource current, ResourceIdentity id, ResourceEventType type, Instant now, Commands.Meta meta,
            String jobId, ObjectNode data) {
        ResourceState before = current == null ? null : current.state();
        ResourceState after = Transitions.next(before, type, current);
        long version = current == null ? 1 : current.version() + 1;
        String wfrn = current == null ? id.wfrn() : current.wfrn();
        String accountId = current == null ? id.accountId() : current.accountId();
        String region = current == null ? id.region() : current.region();
        Instant at = current != null && current.updatedAt() != null && now.isBefore(current.updatedAt()) ? current.updatedAt() : now;
        return new ResourceEvent(null, type, wfrn, accountId, region, version, null, at, after, meta == null ? null : meta.actor(), jobId,
                meta == null ? null : meta.requestId(), data, null, null);
    }

    private static ObjectNode job(String jobId, String workflow, String workflowVersion) {
        ObjectNode data = Json.object();
        Json.putIf(data, "jobId", jobId);
        Json.putIf(data, "workflow", workflow);
        Json.putIf(data, "workflowVersion", workflowVersion);
        return data;
    }

    private static void requireExists(Resource current) {
        Objects.requireNonNull(current, "the command needs an existing resource");
        if (current.deleted()) {
            throw new TombstoneException(current.tombstone());
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidInputException("a required field is missing", List.of(Problem.error("required", field, "required")));
        }
    }

    /** 409 {@code version.mismatch} when the caller named a version and the resource moved on. */
    static void checkVersion(Resource current, Commands.Meta meta) {
        if (meta == null || meta.expectedVersion() == null) {
            return;
        }
        long actual = current == null ? 0 : current.version();
        if (meta.expectedVersion() != actual) {
            throw new ConflictException("version.mismatch: the resource is at version " + actual + ", not " + meta.expectedVersion(),
                    Map.of("expectedVersion", meta.expectedVersion(), "version", actual));
        }
    }

    /** The drift fields of a finding as JSON, for tests and the API. */
    public static List<DriftField> fields(JsonNode node) {
        return new ArrayList<>(ResourceFold.fields(node));
    }

    /** The registry's lineage on an event, when the command knows it: the registry ids the resource was built from. */
    private static void lineage(ObjectNode data, List<String> builtFrom) {
        if (builtFrom != null) {
            var list = data.putArray("builtFrom");
            builtFrom.stream().filter(id -> id != null && !id.isBlank()).distinct().forEach(list::add);
        }
    }

}
