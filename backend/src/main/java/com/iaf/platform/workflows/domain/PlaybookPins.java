package com.iaf.platform.workflows.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.expressions.ExpressionEngine;

/**
 * Pinning git playbooks at publish: an ansible step with {@code playbook.git.ref} and no
 * {@code playbook.commit} gets the commit the ref names when the version is published, so every
 * run of that version uses the same code. The commit is written into the version itself, where
 * the change request's diff shows it.
 */
public final class PlaybookPins {

    /** One step to pin: its git source and the optional {@code vault:} credential of a private repository. */
    public record Pin(String step, String repo, String ref, String credential) {
    }

    /** What a publish did: the steps pinned (step id to commit) and why the others could not be. */
    public record Outcome(Map<String, String> pinned, List<String> failures) {
        public static final Outcome NONE = new Outcome(Map.of(), List.of());
    }

    private PlaybookPins() {
    }

    /** The ansible steps whose git playbook names a ref but no commit; templated refs are left alone. */
    public static List<Pin> needed(WorkflowDefinition def) {
        List<Pin> out = new ArrayList<>();
        for (Step s : def.allSteps()) {
            if (s.type() != StepType.ANSIBLE) {
                continue;
            }
            JsonNode pb = s.withNode("playbook");
            if (pb == null || !pb.isObject() || !pb.path("git").isObject() || !text(pb, "commit").isEmpty()) {
                continue;
            }
            JsonNode git = pb.get("git");
            String repo = text(git, "repo");
            String ref = text(git, "ref");
            if (repo.isEmpty() || ref.isEmpty() || ExpressionEngine.isTemplate(repo) || ExpressionEngine.isTemplate(ref)) {
                continue;
            }
            String credential = text(git, "credential");
            out.add(new Pin(s.id(), repo, ref, credential.isEmpty() ? null : credential));
        }
        return out;
    }

    /** A copy of {@code def} with {@code playbook.commit} set on each step in {@code commits}; {@code def} is not touched. */
    public static WorkflowDefinition apply(WorkflowDefinition def, Map<String, String> commits) {
        if (commits.isEmpty()) {
            return def;
        }
        WorkflowDefinition copy = WorkflowYaml.fromJson(WorkflowYaml.toJson(def).deepCopy());
        for (Step s : copy.allSteps()) {
            String commit = commits.get(s.id());
            if (commit != null && s.type() == StepType.ANSIBLE && s.withNode("playbook") instanceof ObjectNode pb) {
                pb.put("commit", commit);
            }
        }
        return copy;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() || !v.isValueNode() ? "" : v.asText().trim();
    }
}
