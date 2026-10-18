package com.iaf.platform.workflows.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.common.domain.Problem;
import com.iaf.platform.common.domain.Problem.Fix;
import com.iaf.platform.common.ports.PolicyPort.PolicyDecision;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.workflows.domain.ChangeRequest.Check;
import com.iaf.platform.workflows.domain.ContractRules.Consumer;

/**
 * The pure CI checks of a change request: schema, checks (lint), ansible-lint, policy, replay,
 * diff, yaml. The command handler gathers what the policy and replay checks need and hands it
 * here; everything else is judged from the definition alone.
 */
public final class PublishChecks {

    public static final String PASSED = "passed";
    public static final String FAILED = "failed";
    public static final String SKIPPED = "skipped";

    private PublishChecks() {
    }

    /**
     * The stored file matches the Workflow schema (packages/workflow-checks/schemas/workflow.schema.json),
     * and its API input is a JSON Schema of an object that the validator compiles and that refers
     * to nothing outside itself.
     */
    public static Check schema(WorkflowDefinition def) {
        List<String> failures = new ArrayList<>(WorkflowSchema.validate(WorkflowYaml.toJson(def)).stream().map(Problem::message).toList());
        JsonNode input = def.inputSchema();
        if (input != null && (!input.isObject() || (input.has("type") && !"object".equals(input.path("type").asText())))) {
            failures.add("api.input is not a JSON Schema of type object");
        } else if (input != null) {
            WorkflowChecks.inputSchemaProblems(input).forEach(p -> failures.add(p.message()));
        }
        if (!failures.isEmpty()) {
            return new Check("schema", "Schema", FAILED, String.join("; ", failures));
        }
        int fields = input == null ? 0 : input.path("properties").size();
        return new Check("schema", "Schema", PASSED, "kind Workflow, " + def.allSteps().size() + " steps, " + fields + " input fields");
    }

    /** The designer checks: errors fail, warnings are listed. */
    public static Check checks(List<Problem> problems) {
        long errors = problems.stream().filter(Problem::isError).count();
        long warnings = problems.size() - errors;
        String detail = errors + " errors, " + warnings + " warnings";
        List<String> lines = problems.stream().map(PublishChecks::line).toList();
        if (!lines.isEmpty()) {
            detail += ": " + String.join("; ", lines);
        }
        return new Check("checks", "Checks", errors > 0 ? FAILED : PASSED, detail);
    }

    /**
     * Passes when every ansible step is pinned: a git playbook to a commit, an upload to a digest
     * of 64 hex digits, a library playbook through its provider's commit. Skipped when the
     * workflow has no ansible step.
     */
    public static Check ansibleLint(WorkflowDefinition def, CheckContext ctx) {
        return ansibleLint(def, ctx, null);
    }

    /**
     * As {@link #ansibleLint(WorkflowDefinition, CheckContext)}, after the publish pinned git
     * playbooks: a ref that did not resolve fails the check with the reason; the pinned steps
     * are named in the detail.
     */
    public static Check ansibleLint(WorkflowDefinition def, CheckContext ctx, PlaybookPins.Outcome pins) {
        List<Step> ansible = def.allSteps().stream().filter(s -> s.type() == StepType.ANSIBLE).toList();
        if (ansible.isEmpty()) {
            return new Check("ansible-lint", "ansible-lint", SKIPPED, "no ansible step");
        }
        if (pins != null && !pins.failures().isEmpty()) {
            // the step whose ref did not resolve is unpinned as well; the reason is the message
            return new Check("ansible-lint", "ansible-lint", FAILED, "could not pin to a commit: " + String.join("; ", pins.failures()));
        }
        List<String> failures = new ArrayList<>();
        List<String> unpinned = ansible.stream().filter(s -> pinState(s, ctx) == PinState.UNPINNED).map(Step::id).toList();
        if (!unpinned.isEmpty()) {
            failures.add("not pinned to a commit: " + String.join(", ", unpinned));
        }
        List<String> badDigests = ansible.stream().filter(s -> pinState(s, ctx) == PinState.BAD_DIGEST).map(Step::id).toList();
        if (!badDigests.isEmpty()) {
            failures.add("upload digest is not 64 hex digits (playbook.digest): " + String.join(", ", badDigests));
        }
        if (!failures.isEmpty()) {
            return new Check("ansible-lint", "ansible-lint", FAILED, String.join("; ", failures));
        }
        String detail = ansible.size() + " playbook(s) pinned to a commit";
        if (pins != null && !pins.pinned().isEmpty()) {
            detail += "; pinned at publish: " + pins.pinned().entrySet().stream()
                    .map(e -> e.getKey() + " to " + e.getValue().substring(0, Math.min(12, e.getValue().length()))).collect(Collectors.joining(", "));
        }
        return new Check("ansible-lint", "ansible-lint", PASSED, detail);
    }

    /** How an ansible step's playbook is pinned: to a commit or a digest, not at all, or to a digest that is not one. */
    enum PinState {
        PINNED, UNPINNED, BAD_DIGEST
    }

    static boolean pinned(Step s, CheckContext ctx) {
        return pinState(s, ctx) == PinState.PINNED;
    }

    static PinState pinState(Step s, CheckContext ctx) {
        JsonNode pb = s.withNode("playbook");
        if (pb == null || pb.isNull()) {
            return PinState.UNPINNED;
        }
        if (pb.isTextual()) {
            return libraryPinned(pb.asText(), ctx) ? PinState.PINNED : PinState.UNPINNED;
        }
        if (!pb.isObject()) {
            return PinState.UNPINNED;
        }
        if (pb.path("commit").asText(null) != null) {
            return PinState.PINNED;
        }
        if (pb.path("upload").isObject()) {
            String sha256 = pb.path("upload").path("sha256").asText(null);
            if (sha256 != null) {
                return PlaybookPins.DIGEST.matcher(sha256).matches() ? PinState.PINNED : PinState.BAD_DIGEST;
            }
        }
        JsonNode library = pb.get("library");
        return library != null && library.isTextual() && libraryPinned(library.asText(), ctx) ? PinState.PINNED : PinState.UNPINNED;
    }

    private static boolean libraryPinned(String ref, CheckContext ctx) {
        int slash = ref.indexOf('/');
        if (slash <= 0) {
            return false;
        }
        Optional<ProviderDefinition> p = ctx.provider(ref.substring(0, slash));
        return p.map(d -> d.spec().source() != null && d.spec().source().path("commit").asText(null) != null).orElse(false);
    }

    public static Check policy(PolicyDecision decision) {
        String rules = decision.rules() == null || decision.rules().isEmpty() ? ""
                : ": " + decision.rules().stream().map(r -> r.id() + " " + r.result() + (r.message() == null ? "" : " (" + r.message() + ")")).collect(Collectors.joining("; "));
        return new Check("policy", "Policy", decision.allowed() ? PASSED : FAILED, decision.engine() + " says " + decision.decision() + rules);
    }

    /** @param previousVersion the version whose jobs were replayed, null when there was none */
    public static Check replay(ReplayResult result, String previousVersion) {
        if (previousVersion == null) {
            return new Check("replay", "Replay", SKIPPED, "no previous published version");
        }
        if (result.passed()) {
            return new Check("replay", "Replay", PASSED, result.replayed() + " job(s) of " + previousVersion + " replayed");
        }
        String detail = result.divergences().stream()
                .map(d -> d.jobId() + (d.step() == null ? "" : " at " + d.step()) + ": " + d.reason())
                .collect(Collectors.joining("; "));
        return new Check("replay", "Replay", FAILED, result.divergences().size() + " of " + result.replayed() + " replayed job(s) diverge: " + detail);
    }

    public static Check replayUnavailable(String reason) {
        return new Check("replay", "Replay", FAILED, "replay unavailable: " + reason);
    }

    public static Check diff(String diff, String previousVersion) {
        UnifiedDiff.Stats stats = UnifiedDiff.stats(diff);
        if (previousVersion == null) {
            return new Check("diff", "Diff", PASSED, "new workflow, " + stats.added() + " lines");
        }
        if (stats.unchanged()) {
            return new Check("diff", "Diff", PASSED, "no change against " + previousVersion);
        }
        return new Check("diff", "Diff", PASSED, stats + " against " + previousVersion);
    }

    /**
     * Fails when another published workflow already answers one of this workflow's routes: the
     * invoke route, the batch route or the preview route. Parameter names do not matter, so
     * {@code /vms/{id}} and {@code /vms/{vmId}} are the same route.
     */
    public static Check routes(WorkflowDefinition def, List<WorkflowDefinition> published) {
        List<String> clashes = routeClashes(def, published);
        if (clashes.isEmpty()) {
            return new Check("routes", "Routes", PASSED, def.method() + " " + def.path() + ", POST " + def.batchPath());
        }
        return new Check("routes", "Routes", FAILED, String.join("; ", clashes));
    }

    /** Each route of {@code def} another published workflow already answers: {@code POST /api/v1/vms is served by vm-create}. */
    static List<String> routeClashes(WorkflowDefinition def, List<WorkflowDefinition> published) {
        Map<String, String> taken = new LinkedHashMap<>();
        for (WorkflowDefinition other : published) {
            if (other != null && !other.name().equals(def.name())) {
                routeKeys(other).forEach(k -> taken.putIfAbsent(k, other.name()));
            }
        }
        return routeKeys(def).stream().filter(taken::containsKey).map(k -> k + " is served by " + taken.get(k)).toList();
    }

    /**
     * Fails when this version breaks its consumers without a new major version ({@link
     * ContractRules#breakingChanges}). The detail lists every consumer the version reaches: run
     * steps whose range accepts it, {@code orCreateWith} inputs, and the workflows that use a
     * resource type it registers. A breaking change with a major bump passes and still lists
     * them, with the run steps that stay on the old major. Skipped for a first version.
     */
    public static Check contracts(WorkflowDefinition next, WorkflowDefinition previous, DependencyRules.Graph graph) {
        if (previous == null) {
            return new Check("contracts", "Contracts", SKIPPED, "no previous published version");
        }
        List<String> changes = ContractRules.breakingChanges(previous, next);
        List<Consumer> affected = affected(next, graph);
        String consumers = affected.isEmpty() ? "none" : affected.stream().map(Consumer::describe).collect(Collectors.joining(", "));
        if (changes.isEmpty()) {
            return new Check("contracts", "Contracts", PASSED, "no breaking change against " + previous.version()
                    + (affected.isEmpty() ? "" : "; consumers: " + consumers));
        }
        String what = String.join("; ", changes);
        if (ContractRules.isMajorBump(previous.version(), next.version())) {
            List<Consumer> staying = ContractRules.staying(graph, next.name(), next.version());
            return new Check("contracts", "Contracts", PASSED, "new major version " + next.version() + " with breaking changes against " + previous.version()
                    + ": " + what + ". Affected consumers: " + consumers
                    + (staying.isEmpty() ? "" : ". Staying on the old major: " + staying.stream().map(Consumer::describe).collect(Collectors.joining(", "))));
        }
        return new Check("contracts", "Contracts", FAILED, next.version() + " breaks the contract of " + previous.version() + " without a new major version: "
                + what + ". Affected consumers: " + consumers);
    }

    private static List<Consumer> affected(WorkflowDefinition next, DependencyRules.Graph graph) {
        Set<String> types = new TreeSet<>(graph.typesProducedBy(next.name()));
        types.addAll(ContractRules.registered(next).keySet());
        return ContractRules.affected(graph, next.name(), next.version(), types);
    }

    /**
     * The change-request checks judged from the files alone (ansible-lint, routes, contracts) as
     * problems, so the validate endpoint reports what the change request would fail on. Policy
     * and replay need the engine and stay with the change request.
     *
     * @param def       the definition with the version it would be published as
     * @param previous  the current published version of the same workflow, null when there is none
     */
    public static List<Problem> problems(WorkflowDefinition def, CheckContext ctx, List<WorkflowDefinition> published, WorkflowDefinition previous,
            DependencyRules.Graph graph) {
        List<Problem> out = new ArrayList<>();
        // a digest that is not one is already playbook.digest from the designer checks
        def.allSteps().stream().filter(s -> s.type() == StepType.ANSIBLE && pinState(s, ctx) == PinState.UNPINNED)
                .forEach(s -> out.add(Problem.error("publish.ansibleLint", s.id(), "playbook",
                        "The ansible-lint check of the change request fails: pin the playbook to a commit")));
        if (def.path() != null) {
            routeClashes(def, published).forEach(c -> out.add(Problem.error("publish.routes", null, "api.path", "The routes check of the change request fails: " + c)));
        }
        Check contracts = contracts(def, previous, graph);
        if (FAILED.equals(contracts.status())) {
            String major = (Semver.parse(previous.version()).major() + 1) + ".0.0";
            out.add(Problem.error("publish.contracts", null, "metadata.version", "The contracts check of the change request fails: " + contracts.detail(),
                    Fix.replace("Publish as " + major, major)));
        }
        return out;
    }

    static List<String> routeKeys(WorkflowDefinition d) {
        List<String> keys = new ArrayList<>();
        keys.add(d.method() + " " + shape(d.path()));
        keys.add("POST " + shape(d.batchPath()));
        if (d.previewEnabled()) {
            keys.add("POST " + shape(d.path() + ":preview"));
        }
        return keys;
    }

    private static String shape(String path) {
        return path.replaceAll("\\{[^/{}]+}", "{}");
    }

    /** The canonical YAML round-trips to the same content hash. */
    public static Check yaml(WorkflowRecord record) {
        try {
            WorkflowDefinition again = WorkflowYaml.parse(record.yaml());
            String hash = WorkflowYaml.contentHash(again);
            if (!hash.equals(record.contentHash())) {
                return new Check("yaml", "YAML", FAILED, "canonical YAML does not round-trip (" + record.contentHash() + " vs " + hash + ")");
            }
            return new Check("yaml", "YAML", PASSED, "canonical, sha256 " + hash.substring(0, 12));
        } catch (IllegalArgumentException e) {
            return new Check("yaml", "YAML", FAILED, e.getMessage());
        }
    }

    /** The checks that stop a merge. */
    public static List<Check> failed(List<Check> checks) {
        List<Check> out = new ArrayList<>();
        if (checks != null) {
            checks.stream().filter(c -> !c.passed()).forEach(out::add);
        }
        return out;
    }

    private static String line(Problem p) {
        return p.level().json() + " " + p.code() + (p.step() == null ? "" : " @" + p.step()) + (p.field() == null ? "" : "." + p.field()) + " - " + p.message();
    }
}
