package com.iaf.platform.workflows.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

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

    /** An upload's digest as {@code POST /playbooks:upload} answers it: 64 lowercase hex digits. */
    public static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");

    /** One step to pin: its git source and the optional {@code vault:} credential of a private repository. */
    public record Pin(String step, String repo, String ref, String credential) {
    }

    /** One uploaded playbook: the step and the digest the file names (as written, judged by {@link #DIGEST}). */
    public record Upload(String step, String sha256) {
        public boolean validDigest() {
            return sha256 != null && DIGEST.matcher(sha256).matches();
        }
    }

    /** What a publish did: the steps pinned (step id to commit) and why the others could not be. */
    public record Outcome(Map<String, String> pinned, List<String> failures) {
        public static final Outcome NONE = new Outcome(Map.of(), List.of());
    }

    /** A git playbook pinned to a commit, as the linter reads it: the step, the repository at the commit, the playbook inside it. */
    public record Source(String step, String repo, String commit, String path, String credential) {
        /** {@code github.com/acme/ops@4e1d9a2c7b0f vm/restart.yml} */
        public String describe() {
            return repo + "@" + commit.substring(0, Math.min(12, commit.length())) + " " + path;
        }
    }

    /**
     * What the playbook linter ({@code platform.playbooks.lint-command}) said at publish: the
     * steps whose playbook it ran on, and why each one that failed (or could not be linted) did.
     * {@link #NONE} when no linter is configured or no git playbook is pinned.
     */
    public record Lint(List<String> linted, List<String> findings) {
        public static final Lint NONE = new Lint(List.of(), List.of());
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

    /**
     * The ansible steps whose git playbook names a commit, for the linter; templated repositories,
     * paths and commits are left alone (the step judges them when it runs).
     */
    public static List<Source> pinnedSources(WorkflowDefinition def) {
        List<Source> out = new ArrayList<>();
        for (Step s : def.allSteps()) {
            if (s.type() != StepType.ANSIBLE) {
                continue;
            }
            JsonNode pb = s.withNode("playbook");
            if (pb == null || !pb.isObject() || !pb.path("git").isObject()) {
                continue;
            }
            JsonNode git = pb.get("git");
            String repo = text(git, "repo");
            String path = text(git, "path");
            String commit = text(pb, "commit");
            if (repo.isEmpty() || path.isEmpty() || commit.isEmpty()
                    || ExpressionEngine.isTemplate(repo) || ExpressionEngine.isTemplate(path) || ExpressionEngine.isTemplate(commit)) {
                continue;
            }
            String credential = text(git, "credential");
            out.add(new Source(s.id(), repo, commit, path, credential.isEmpty() ? null : credential));
        }
        return out;
    }

    /** A path inside a repository: relative, forward slashes, no {@code ..} segment. */
    public static boolean insideRepository(String path) {
        return path != null && !path.isBlank() && !path.startsWith("/") && !path.contains("\\") && !List.of(path.split("/")).contains("..");
    }

    /** The ansible steps that run an uploaded playbook, with the digest each names (templated digests are left alone). */
    public static List<Upload> uploads(WorkflowDefinition def) {
        List<Upload> out = new ArrayList<>();
        for (Step s : def.allSteps()) {
            if (s.type() != StepType.ANSIBLE) {
                continue;
            }
            JsonNode pb = s.withNode("playbook");
            if (pb == null || !pb.isObject() || !pb.path("upload").isObject()) {
                continue;
            }
            String sha = text(pb.get("upload"), "sha256");
            if (sha.isEmpty() || ExpressionEngine.isTemplate(sha)) {
                continue;
            }
            out.add(new Upload(s.id(), sha));
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
