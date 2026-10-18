package com.iaf.platform.catalog.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.catalog.domain.BatchProblemsException;
import com.iaf.platform.catalog.domain.BatchProblemsException.RowProblems;
import com.iaf.platform.catalog.domain.InputProblemsException;
import com.iaf.platform.catalog.domain.Inputs;
import com.iaf.platform.catalog.domain.PolicyDeniedException;
import com.iaf.platform.common.domain.CommandAccepted;
import com.iaf.platform.common.domain.ConflictException;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.common.domain.Problem;
import com.iaf.platform.common.ports.PolicyPort;
import com.iaf.platform.common.ports.PolicyPort.PolicyDecision;
import com.iaf.platform.common.ports.PolicyPort.PolicyInput;
import com.iaf.platform.common.schema.Schemas;
import com.iaf.platform.gateway.application.JobQuotas;
import com.iaf.platform.identity.domain.AccessRules;
import com.iaf.platform.runs.domain.InputDefaults;
import com.iaf.platform.runs.domain.Preview;
import com.iaf.platform.runs.ports.inbound.RunsCommands;
import com.iaf.platform.runs.ports.inbound.RunsCommands.StartBatch;
import com.iaf.platform.runs.ports.inbound.RunsCommands.StartJob;
import com.iaf.platform.workflows.domain.CreateRequests;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowDefinition.RolloutPolicy;
import com.iaf.platform.workflows.domain.WorkflowRecord;
import com.iaf.platform.workflows.domain.WorkflowStatus;
import com.iaf.platform.workflows.ports.inbound.WorkflowQueries;

import reactor.core.publisher.Mono;

/**
 * The one path from "someone calls a generated operation" to "a job starts", shared by the REST
 * gateway and the MCP server: take a token from the caller's team quota (a batch takes one),
 * validate the input against {@code api.input}, judge RBAC
 * ({@code policy.rbac}), ask the policy engine, compute the preview when the workflow publishes
 * one, then start the job (or the batch) through the runs slice. A call pinned to a version is
 * judged against that version's definition and runs it without the canary split.
 */
@Service
public class WorkflowInvoker {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    public static final String JOB_LINK = "/api/v1/jobs/";

    private final RunsCommands runs;
    private final PolicyPort policy;
    private final JobQuotas quotas;
    private final WorkflowQueries workflows;

    public WorkflowInvoker(RunsCommands runs, PolicyPort policy) {
        this(runs, policy, JobQuotas.unlimited());
    }

    /** {@code quotas}: every invoke or batch takes a token from the caller's team (429 when there is none), given back when the start refuses it. */
    public WorkflowInvoker(RunsCommands runs, PolicyPort policy, JobQuotas quotas) {
        this(runs, policy, quotas, null);
    }

    /** {@code workflows}: where a pinned call finds its version; without it the record given is judged and the runs slice resolves the pin. */
    @Autowired
    public WorkflowInvoker(RunsCommands runs, PolicyPort policy, JobQuotas quotas, WorkflowQueries workflows) {
        this.runs = runs;
        this.policy = policy;
        this.quotas = quotas;
        this.workflows = workflows;
    }

    /** What the caller gets back: {@code { jobId, status, links: { job } }}. */
    public record Started(String jobId, String status, Map<String, String> links) {
        public static Started of(CommandAccepted accepted) {
            Map<String, String> links = new LinkedHashMap<>();
            links.put("job", JOB_LINK + accepted.id());
            if (accepted.links() != null) {
                accepted.links().forEach(links::putIfAbsent);
            }
            return new Started(accepted.id(), accepted.status() == null ? "queued" : accepted.status(), links);
        }
    }

    /** {@code { jobId, rows, status, links: { job } }}. */
    public record BatchStarted(String jobId, int rows, String status, Map<String, String> links) {
        public static BatchStarted of(CommandAccepted accepted, int rows) {
            Started s = Started.of(accepted);
            return new BatchStarted(s.jobId(), rows, s.status(), s.links());
        }
    }

    /** The validated rows of a batch and how they roll out. */
    public record BatchPlan(List<JsonNode> rows, JsonNode defaults, RolloutPolicy rollout) {
    }

    // ---------------------------------------------------------------- one job

    public Mono<Started> invoke(WorkflowRecord workflow, JsonNode input, Identity who, String channel, String traceId) {
        return invoke(workflow, input, who, channel, traceId, null);
    }

    /** {@code version}: run exactly that version (published or retired), bypassing the canary split; null lets the release router pick. */
    public Mono<Started> invoke(WorkflowRecord workflow, JsonNode input, Identity who, String channel, String traceId, String version) {
        return Mono.defer(() -> pinned(workflow, version)).flatMap(rec -> {
            WorkflowDefinition d = rec.definition();
            ObjectNode in = withDefaults(d, Inputs.merge(input, null));
            validate(d, in);
            AccessRules.requireInvoke(who, rbac(d), d.name());
            return decide(d, rec.version(), who, in, pinContext(context(channel, traceId, 0), version))
                    // a token only once the request is valid, allowed and not denied; given back when the
                    // start still refuses it (an unknown resource id is a 400 only the runs slice can tell)
                    .then(quotas.charged(who, preview(d, in, who)
                            .flatMap(p -> runs.startJob(new StartJob(d.name(), version, in, who, channel, traceId, p.orElse(null), null)))))
                    .map(Started::of);
        });
    }

    /** {@code :preview}: the blast radius without starting anything. The input is validated and RBAC judged as for a run. */
    public Mono<Preview> preview(WorkflowRecord workflow, JsonNode input, Identity who) {
        return preview(workflow, input, who, null);
    }

    /** The preview of the pinned version, when {@code version} is given. */
    public Mono<Preview> preview(WorkflowRecord workflow, JsonNode input, Identity who, String version) {
        return Mono.defer(() -> pinned(workflow, version)).flatMap(rec -> {
            WorkflowDefinition d = rec.definition();
            ObjectNode in = withDefaults(d, Inputs.merge(input, null));
            validate(d, in);
            AccessRules.requireInvoke(who, rbac(d), d.name());
            return runs.preview(d, in, who);
        });
    }

    /**
     * The record a call runs: the published one, or the pinned version. A pin names a version
     * that was published (it may be retired since); a draft or a candidate is 409, an unknown
     * version 404.
     */
    Mono<WorkflowRecord> pinned(WorkflowRecord workflow, String version) {
        if (version == null || version.equals(workflow.version()) || workflows == null) {
            return Mono.just(workflow);
        }
        return workflows.version(workflow.name(), version)
                .switchIfEmpty(Mono.error(() -> new NotFoundException("workflow version", workflow.name() + "@" + version)))
                .flatMap(rec -> rec.status() == WorkflowStatus.PUBLISHED || rec.status() == WorkflowStatus.RETIRED
                        ? Mono.just(rec)
                        : Mono.error(new ConflictException(workflow.name() + "@" + version + " is " + rec.status().json()
                                + "; only published or retired versions can be pinned")));
    }

    private static ObjectNode pinContext(ObjectNode ctx, String pin) {
        if (pin != null) {
            ctx.put("pinnedVersion", pin);
        }
        return ctx;
    }

    // ---------------------------------------------------------------- a batch

    /**
     * {@code body} is {@code { items, defaults?, rollout? }}; {@code params} are path parameters
     * applied on top of every row. Every row is validated before anything starts; RBAC and policy
     * are judged once, one preview is computed for the batch (from its first row).
     */
    public Mono<BatchStarted> batch(WorkflowRecord workflow, JsonNode body, Map<String, String> params, Identity who, String channel) {
        return batch(workflow, body, params, who, channel, null);
    }

    /** {@code version}: every row runs that version, bypassing the canary split; null lets the release router pick. */
    public Mono<BatchStarted> batch(WorkflowRecord workflow, JsonNode body, Map<String, String> params, Identity who, String channel, String version) {
        return batch(workflow, body, params, who, channel, version, null);
    }

    /** {@code traceId}: the trace the batch and its rows run in (the request's); null starts a new one. */
    public Mono<BatchStarted> batch(WorkflowRecord workflow, JsonNode body, Map<String, String> params, Identity who, String channel, String version,
            String traceId) {
        return Mono.defer(() -> pinned(workflow, version)).flatMap(rec -> {
            WorkflowDefinition d = rec.definition();
            BatchPlan plan = plan(d, body, params);
            AccessRules.requireInvoke(who, rbac(d), d.name());
            ObjectNode policyInput = F.objectNode();
            policyInput.set("defaults", plan.defaults());
            ArrayNode rows = policyInput.putArray("items");
            plan.rows().forEach(rows::add);
            return decide(d, rec.version(), who, policyInput, pinContext(context(channel, traceId, plan.rows().size()), version))
                    // a token only once every row is valid, the caller allowed and the policy not against;
                    // given back when the start still refuses the batch
                    .then(quotas.charged(who, preview(d, plan.rows().get(0), who)
                            .flatMap(p -> runs.startBatch(new StartBatch(d.name(), version, plan.rows(), plan.defaults(), plan.rollout(), who,
                                    channel, p.orElse(null), traceId)))))
                    .map(a -> BatchStarted.of(a, plan.rows().size()));
        });
    }

    /** Merge defaults under every row, apply the path parameters, validate every row. */
    public BatchPlan plan(WorkflowDefinition d, JsonNode body, Map<String, String> params) {
        if (body == null || !body.isObject()) {
            throw new IllegalArgumentException("A batch body is { items, defaults?, rollout? }");
        }
        JsonNode items = body.get("items");
        if (items == null || !items.isArray() || items.isEmpty()) {
            throw new IllegalArgumentException("items must be a non-empty array of inputs");
        }
        int max = d.batchPolicy().max();
        if (max > 0 && items.size() > max) {
            throw new IllegalArgumentException("A batch of " + d.name() + " holds at most " + max + " rows, got " + items.size());
        }
        JsonNode defaults = body.get("defaults");
        if (defaults != null && !defaults.isNull() && !defaults.isObject()) {
            throw new IllegalArgumentException("defaults must be an object");
        }
        ObjectNode dflt = Inputs.merge(defaults, null);
        List<JsonNode> rows = new ArrayList<>();
        List<RowProblems> invalid = new ArrayList<>();
        JsonNode schema = d.inputSchema();
        int i = 0;
        for (JsonNode item : items) {
            if (!item.isObject()) {
                invalid.add(new RowProblems(i, List.of(Problem.error("schema", null, null, "each item is an object"))));
                rows.add(F.objectNode());
            } else {
                ObjectNode row = withDefaults(d, Inputs.of(Inputs.merge(dflt, item), params, schema));
                List<Problem> problems = CreateRequests.inputProblems(d, row);
                if (!problems.isEmpty()) {
                    invalid.add(new RowProblems(i, problems));
                }
                rows.add(row);
            }
            i++;
        }
        if (!invalid.isEmpty()) {
            throw new BatchProblemsException(invalid.size() + " of " + items.size() + " rows are invalid; nothing was started", invalid);
        }
        return new BatchPlan(List.copyOf(rows), dflt, rollout(body.get("rollout"), d.batchPolicy().rollout()));
    }

    static RolloutPolicy rollout(JsonNode node, RolloutPolicy dflt) {
        RolloutPolicy base = dflt == null ? RolloutPolicy.DEFAULT : dflt;
        if (node == null || !node.isObject()) {
            return base;
        }
        List<Integer> waves = base.waves();
        JsonNode w = node.get("waves");
        if (w != null && w.isArray() && !w.isEmpty()) {
            List<Integer> parsed = new ArrayList<>();
            int prev = 0;
            for (JsonNode x : w) {
                if (!x.isNumber() || x.asInt() <= prev || x.asInt() > 100) {
                    throw new IllegalArgumentException("rollout.waves must be ascending percentages up to 100");
                }
                prev = x.asInt();
                parsed.add(x.asInt());
            }
            waves = parsed;
        }
        int maxParallel = node.path("maxParallel").asInt(base.maxParallel());
        if (maxParallel < 1) {
            throw new IllegalArgumentException("rollout.maxParallel must be at least 1");
        }
        int stop = node.path("stopIfFailedPercent").asInt(base.stopIfFailedPercent());
        if (stop < 0 || stop > 100) {
            throw new IllegalArgumentException("rollout.stopIfFailedPercent is a percentage");
        }
        return new RolloutPolicy(waves, maxParallel, stop);
    }

    // ---------------------------------------------------------------- the gates

    /** The schema's top-level defaults filled in, so a caller may leave a defaulted field out even when the schema requires it. */
    static ObjectNode withDefaults(WorkflowDefinition d, ObjectNode input) {
        return (ObjectNode) InputDefaults.apply(d.inputSchema(), input);
    }

    /** The input schema; a request to build a resource first is judged against its producer when the job starts. */
    public static void validate(WorkflowDefinition d, JsonNode input) {
        List<Problem> problems = CreateRequests.inputProblems(d, input);
        if (!problems.isEmpty()) {
            throw new InputProblemsException("The input of " + d.name() + " has " + problems.size() + " problem" + (problems.size() == 1 ? "" : "s"), problems);
        }
    }

    private static List<String> rbac(WorkflowDefinition d) {
        return d.policy() == null ? null : d.policy().rbac();
    }

    private Mono<PolicyDecision> decide(WorkflowDefinition d, String version, Identity who, JsonNode input, JsonNode context) {
        return policy.evaluate(new PolicyInput(PolicyPort.Phase.INVOKE, who, d.name(), version == null ? d.version() : version, input, context))
                .defaultIfEmpty(PolicyDecision.allow(policy.engine()))
                .flatMap(decision -> {
                    if (!decision.allowed()) {
                        String why = decision.rules() == null || decision.rules().isEmpty() ? ""
                                : ": " + String.join("; ", decision.rules().stream().map(r -> r.message() == null ? r.id() : r.message()).toList());
                        return Mono.error(new PolicyDeniedException("Policy denies " + d.name() + " for " + who.name() + why, decision.rules(), decision.engine()));
                    }
                    return Mono.just(decision);
                });
    }

    private Mono<java.util.Optional<Preview>> preview(WorkflowDefinition d, JsonNode input, Identity who) {
        if (!d.previewEnabled()) {
            return Mono.just(java.util.Optional.empty());
        }
        return runs.preview(d, input, who).map(java.util.Optional::of).defaultIfEmpty(java.util.Optional.empty());
    }

    private static ObjectNode context(String channel, String traceId, int rows) {
        ObjectNode ctx = F.objectNode();
        ctx.put("channel", channel);
        if (traceId != null) {
            ctx.put("traceId", traceId);
        }
        if (rows > 0) {
            ctx.put("rows", rows);
        }
        return ctx;
    }
}
