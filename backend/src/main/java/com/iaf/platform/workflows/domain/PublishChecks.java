package com.iaf.platform.workflows.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.catalog.domain.McpTools;
import com.iaf.platform.common.domain.Problem;
import com.iaf.platform.common.domain.Problem.Fix;
import com.iaf.platform.common.ports.PolicyPort.PolicyDecision;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.workflows.domain.ChangeRequest.Check;
import com.iaf.platform.workflows.domain.ContractRules.Consumer;

/**
 * The pure CI checks of a change request: schema, checks (lint), playbooks, policy, replay,
 * contracts, diff, routes, yaml, and requires for one opened with the change requests it
 * requires. The command handler gathers what the policy, replay and playbooks checks need (the
 * engine's verdict, the jobs, the pins and the linter's findings) and hands it here; everything
 * else is judged from the definition alone.
 */
public final class PublishChecks {

    public static final String PASSED = "passed";
    public static final String FAILED = "failed";
    public static final String SKIPPED = "skipped";
    /** The requires check while a change request it requires is still open: not a failure, but the change request does not merge yet. */
    public static final String PENDING = "pending";
    /** The id of the playbooks check (pins resolved, digests present, the linter's findings). */
    public static final String PLAYBOOKS = "playbooks";
    /** The id of the requires check of a change request opened with change requests it requires. */
    public static final String REQUIRES = "requires";
    /** The longest a linter's output gets in the check's detail; the rest is cut. */
    static final int LINT_OUTPUT = 2000;

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
     * The playbooks check, pins resolved and digests present: passes when every ansible step is
     * pinned, a git playbook to a commit, an upload to a digest of 64 hex digits, a library
     * playbook through its provider's commit. Skipped when the workflow has no ansible step.
     */
    public static Check playbooks(WorkflowDefinition def, CheckContext ctx) {
        return playbooks(def, ctx, null, null);
    }

    /**
     * As {@link #playbooks(WorkflowDefinition, CheckContext)}, after the publish pinned git
     * playbooks (a ref that did not resolve fails the check with the reason; the pinned steps
     * are named in the detail) and ran the configured linter on them: each finding fails the
     * check with the linter's output.
     */
    public static Check playbooks(WorkflowDefinition def, CheckContext ctx, PlaybookPins.Outcome pins, PlaybookPins.Lint lint) {
        List<Step> ansible = def.allSteps().stream().filter(s -> s.type() == StepType.ANSIBLE).toList();
        if (ansible.isEmpty()) {
            return new Check(PLAYBOOKS, "Playbooks", SKIPPED, "no ansible step");
        }
        if (pins != null && !pins.failures().isEmpty()) {
            // the step whose ref did not resolve is unpinned as well; the reason is the message
            return new Check(PLAYBOOKS, "Playbooks", FAILED, "could not pin to a commit: " + String.join("; ", pins.failures()));
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
        if (lint != null && !lint.findings().isEmpty()) {
            failures.add("the linter found problems: " + lint.findings().stream().map(PublishChecks::capped).collect(Collectors.joining("; ")));
        }
        if (!failures.isEmpty()) {
            return new Check(PLAYBOOKS, "Playbooks", FAILED, String.join("; ", failures));
        }
        String detail = ansible.size() + " playbook(s) pinned to a commit";
        if (lint != null && !lint.linted().isEmpty()) {
            detail += ", " + lint.linted().size() + " linted";
        }
        if (pins != null && !pins.pinned().isEmpty()) {
            detail += "; pinned at publish: " + pins.pinned().entrySet().stream()
                    .map(e -> e.getKey() + " to " + e.getValue().substring(0, Math.min(12, e.getValue().length()))).collect(Collectors.joining(", "));
        }
        return new Check(PLAYBOOKS, "Playbooks", PASSED, detail);
    }

    /** A finding with the linter's output on one line and cut at {@link #LINT_OUTPUT} characters. */
    private static String capped(String finding) {
        String line = finding.strip().replaceAll("\\s*\\R\\s*", " | ");
        return line.length() <= LINT_OUTPUT ? line : line.substring(0, LINT_OUTPUT - 1) + "…";
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
     * What the registry refuses beyond the change-request checks, as errors: publishing answers
     * 409 with them and opens nothing, {@code :validate} reports them and merging judges them
     * again (another workflow may have been published since). Each is judged against the other
     * published workflows, with {@code def} in place of its own published version.
     */
    public static List<Problem> registryProblems(WorkflowDefinition def, List<WorkflowDefinition> published) {
        List<Problem> out = new ArrayList<>(mcpToolNames(def, published));
        out.addAll(runCycles(def, published));
        return out;
    }

    /**
     * {@code run.cycle}: a run step or {@code orCreateWith} input of {@code def} whose workflow,
     * through what the other published workflows start, ends up starting {@code def} again.
     */
    public static List<Problem> runCycles(WorkflowDefinition def, List<WorkflowDefinition> published) {
        Map<String, Set<String>> runs = DependencyRules.runs(published.stream()
                .filter(o -> o != null && o.name() != null && !o.name().equals(def.name())).toList());
        return DependencyRules.loops(def, n -> runs.getOrDefault(n, Set.of())).stream().map(WorkflowChecks::cycleProblem).toList();
    }

    /**
     * {@code mcp.toolName}: another published workflow already has one of the MCP tools this one
     * would get ({@code <noun>_<verb>} or its batch tool). The MCP server lists one tool per name,
     * so one of the two would silently stop being a tool.
     */
    public static List<Problem> mcpToolNames(WorkflowDefinition def, List<WorkflowDefinition> published) {
        List<String> mine = McpTools.toolNames(def);
        if (mine.isEmpty()) {
            return List.of();
        }
        Map<String, String> taken = new LinkedHashMap<>();
        published.stream().filter(o -> o != null && o.name() != null && !o.name().equals(def.name()))
                .sorted(Comparator.comparing(WorkflowDefinition::name))
                .forEach(o -> McpTools.toolNames(o).forEach(t -> taken.putIfAbsent(t, o.name())));
        return mine.stream().filter(taken::containsKey).findFirst()
                .map(t -> List.of(Problem.error("mcp.toolName", null, "mcp.expose", WorkflowChecks.mcpToolMessage(t, taken.get(t)))))
                .orElse(List.of());
    }

    /**
     * The designer's problems with what publishing refuses: an error replaces the designer's
     * warning of the same code (the designer judged the same thing from a context that can be
     * older), and a problem both report is listed once.
     */
    public static List<Problem> merge(List<Problem> designer, List<Problem> publish) {
        Set<String> refused = publish.stream().filter(Problem::isError).map(Problem::code).collect(Collectors.toSet());
        List<Problem> out = new ArrayList<>(designer.stream().filter(p -> p.isError() || !refused.contains(p.code())).toList());
        publish.stream().filter(p -> !out.contains(p)).forEach(out::add);
        return out;
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
     * The change-request checks judged from the files alone (playbooks, routes, contracts) as
     * problems, so the validate endpoint reports what the change request would fail on, and the
     * {@link #registryProblems} publishing refuses. Policy and replay need the engine, and the
     * linter a checkout of each playbook, so they stay with the change request.
     *
     * @param def       the definition with the version it would be published as
     * @param previous  the current published version of the same workflow, null when there is none
     */
    public static List<Problem> problems(WorkflowDefinition def, CheckContext ctx, List<WorkflowDefinition> published, WorkflowDefinition previous,
            DependencyRules.Graph graph) {
        List<Problem> out = new ArrayList<>();
        // a digest that is not one is already playbook.digest from the designer checks
        def.allSteps().stream().filter(s -> s.type() == StepType.ANSIBLE && pinState(s, ctx) == PinState.UNPINNED)
                .forEach(s -> out.add(Problem.error("publish.playbooks", s.id(), "playbook",
                        "The playbooks check of the change request fails: pin the playbook to a commit")));
        if (def.path() != null) {
            routeClashes(def, published).forEach(c -> out.add(Problem.error("publish.routes", null, "api.path", "The routes check of the change request fails: " + c)));
        }
        Check contracts = contracts(def, previous, graph);
        if (FAILED.equals(contracts.status())) {
            String major = (Semver.parse(previous.version()).major() + 1) + ".0.0";
            out.add(Problem.error("publish.contracts", null, "metadata.version", "The contracts check of the change request fails: " + contracts.detail(),
                    Fix.replace("Publish as " + major, major)));
        }
        out.addAll(registryProblems(def, published));
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

    /**
     * Where a provider change request that a workflow change request requires stands. DONE once it
     * is merged and its provider's current version is its version or a later one, so a workflow
     * that calls what it added finds it on every call; ROLLING_OUT while it is merged but its
     * version does not serve calls yet (a canary still rolling out or rolled back, or the provider
     * still being published); OPEN; CLOSED once it was closed without merging, or is gone, since
     * it never will be done.
     */
    public enum Standing {
        DONE, ROLLING_OUT, OPEN, CLOSED
    }

    /** A change request a workflow change request requires ({@code id}; empty when the store no longer has it) and the version its provider serves now. */
    public record Required(String id, Optional<ChangeRequest> changeRequest, Optional<String> current) {

        /** Each of {@code ids}, looked up now with the current version of its provider. */
        public static List<Required> of(List<String> ids, Function<String, Optional<ChangeRequest>> lookup, Function<String, Optional<String>> current) {
            return ids.stream().map(id -> {
                Optional<ChangeRequest> cr = lookup.apply(id);
                return new Required(id, cr, cr.flatMap(c -> current.apply(c.name())));
            }).toList();
        }

        public Standing standing() {
            if (changeRequest.isEmpty()) {
                return Standing.CLOSED;
            }
            ChangeRequest cr = changeRequest.get();
            return switch (cr.status()) {
                case OPEN -> Standing.OPEN;
                case CLOSED -> Standing.CLOSED;
                case MERGED -> current.filter(v -> atLeast(v, cr.version())).isPresent() ? Standing.DONE : Standing.ROLLING_OUT;
            };
        }

        /** {@code cr_123 (provider vsphere 1.4.0)}, or the bare id of one that is gone. */
        public String named() {
            return changeRequest.map(PublishChecks::named).orElse(id);
        }

        /** Why it is not done: {@code cr_1 (provider vsphere 1.4.1) is open}, {@code ... is merged, but vsphere 1.4.0 still serves calls}. */
        public String why() {
            return switch (standing()) {
                case DONE -> named() + " is merged and published";
                case OPEN -> named() + " is open";
                case CLOSED -> changeRequest.isEmpty() ? id + " is gone" : named() + " is closed";
                case ROLLING_OUT -> named() + " is merged, but " + servedNow();
            };
        }

        private String servedNow() {
            String provider = changeRequest.map(ChangeRequest::name).orElse(id);
            return current.map(v -> provider + " " + v + " still serves calls").orElse(provider + " is not published yet");
        }
    }

    /** Whether provider version {@code version} is {@code wanted} or a later one; versions that differ only after a dash are not later. */
    public static boolean atLeast(String version, String wanted) {
        return version.equals(wanted) || Semver.parse(version).compareTo(Semver.parse(wanted)) > 0;
    }

    /**
     * The requires check of a change request opened with the change requests it requires
     * ({@code ids}, each looked up now, with the current version of its provider): passed once
     * every one is done (merged, and its version or a later one serves calls), pending while one is
     * open or its version is not current yet (a canary rolling out), failed once one was closed
     * without merging, since it never will be. One the lookup no longer finds counts as closed.
     */
    public static Check requires(List<String> ids, Function<String, Optional<ChangeRequest>> lookup, Function<String, Optional<String>> current) {
        List<Required> all = Required.of(ids, lookup, current);
        List<String> closed = all.stream().filter(r -> r.standing() == Standing.CLOSED).map(Required::named).toList();
        if (!closed.isEmpty()) {
            return new Check(REQUIRES, "Requires", FAILED, String.join(", ", closed) + (closed.size() == 1 ? " was" : " were")
                    + " closed without merging; close this change request and publish again");
        }
        List<String> done = all.stream().filter(r -> r.standing() == Standing.DONE).map(Required::named).toList();
        List<String> waiting = new ArrayList<>();
        List<String> open = all.stream().filter(r -> r.standing() == Standing.OPEN).map(Required::named).toList();
        if (!open.isEmpty()) {
            waiting.add("waiting for " + String.join(", ", open) + " to merge");
        }
        all.stream().filter(r -> r.standing() == Standing.ROLLING_OUT)
                .forEach(r -> waiting.add("waiting for " + r.named() + " to serve calls: it is merged, but " + r.servedNow()));
        if (!waiting.isEmpty()) {
            return new Check(REQUIRES, "Requires", PENDING, String.join("; ", waiting) + (done.isEmpty() ? "" : "; published: " + String.join(", ", done)));
        }
        return new Check(REQUIRES, "Requires", PASSED, "published: " + String.join(", ", done));
    }

    /** {@code cr_123 (provider vsphere 1.4.0)}: a change request as a check's detail names it. */
    public static String named(ChangeRequest cr) {
        return cr.id() + " (" + cr.kind().json() + " " + cr.name() + " " + cr.version() + ")";
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
