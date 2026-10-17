package com.iaf.platform.workflows.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.common.domain.Problem;
import com.iaf.platform.common.ports.PolicyPort.PolicyDecision;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.workflows.domain.ChangeRequest.Check;

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

    /** The file parses and its API input is a JSON Schema of an object. */
    public static Check schema(WorkflowDefinition def) {
        JsonNode input = def.inputSchema();
        if (input != null && (!input.isObject() || (input.has("type") && !"object".equals(input.path("type").asText())))) {
            return new Check("schema", "Schema", FAILED, "api.input is not a JSON Schema of type object");
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

    /** Passes when every ansible step is pinned to a commit; skipped when the workflow has none. */
    public static Check ansibleLint(WorkflowDefinition def, CheckContext ctx) {
        List<Step> ansible = def.allSteps().stream().filter(s -> s.type() == StepType.ANSIBLE).toList();
        if (ansible.isEmpty()) {
            return new Check("ansible-lint", "ansible-lint", SKIPPED, "no ansible step");
        }
        List<String> unpinned = ansible.stream().filter(s -> !pinned(s, ctx)).map(Step::id).toList();
        if (unpinned.isEmpty()) {
            return new Check("ansible-lint", "ansible-lint", PASSED, ansible.size() + " playbook(s) pinned to a commit");
        }
        return new Check("ansible-lint", "ansible-lint", FAILED, "not pinned to a commit: " + String.join(", ", unpinned));
    }

    static boolean pinned(Step s, CheckContext ctx) {
        JsonNode pb = s.withNode("playbook");
        if (pb == null || pb.isNull()) {
            return false;
        }
        if (pb.isTextual()) {
            return libraryPinned(pb.asText(), ctx);
        }
        if (!pb.isObject()) {
            return false;
        }
        if (pb.path("commit").asText(null) != null) {
            return true;
        }
        if (pb.has("upload") && pb.path("upload").path("sha256").asText(null) != null) {
            return true;
        }
        JsonNode library = pb.get("library");
        return library != null && library.isTextual() && libraryPinned(library.asText(), ctx);
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

    public static Check policyUnavailable(String reason) {
        return new Check("policy", "Policy", FAILED, "policy engine unavailable: " + reason);
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
