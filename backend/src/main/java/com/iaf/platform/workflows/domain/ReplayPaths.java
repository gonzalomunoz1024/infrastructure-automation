package com.iaf.platform.workflows.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The paths the replay check compares: what a job of the previous version did (its recorded
 * steps) and what the new version does on the same input (its dry run). A path is the sequence
 * of steps taken and, for each provider call, the provider and action it called; names and
 * titles do not count. Live jobs run a fork's branches in parallel, so a path is kept as lines:
 * the main line, and one line per fork branch, each in the order its steps ran. Steps that only
 * run on a failure (the workflow's catch block, a try's catch, compensations) are not part of a
 * path, nor are skipped or pending steps, nor a child workflow's own steps (its job records them).
 */
public final class ReplayPaths {

    /** The main line of a path. */
    public static final String MAIN = "";

    /** One step as a job or a dry run recorded it: its id, status and resolved input ({@code with}). */
    public record Ran(String id, String status, JsonNode input) {
    }

    /** A step taken: its id and, for a provider call, {@code <provider> <action>}. */
    public record Taken(String step, String call) {
        public String describe() {
            return call == null ? step : step + " (" + call + ")";
        }
    }

    /** Where two paths part: the step (of the expected path when it has one there) and why. */
    public record Difference(String step, String reason) {
    }

    private ReplayPaths() {
    }

    /** The path of a run of {@code def}: its lines, the main line first, each fork branch under {@code <fork>#<branch>}. */
    public static Map<String, List<Taken>> path(List<Ran> steps, WorkflowDefinition def) {
        Map<String, String> lineOf = new HashMap<>();
        Set<String> onFailure = new HashSet<>();
        Map<String, Step> byId = new HashMap<>();
        lines(def.steps(), MAIN, false, lineOf, onFailure, byId);
        if (def.workflow() != null && def.workflow().catchFlow() != null && def.workflow().catchFlow().steps() != null) {
            lines(def.workflow().catchFlow().steps(), MAIN, true, lineOf, onFailure, byId);
        }
        Map<String, List<Taken>> out = new LinkedHashMap<>();
        out.put(MAIN, new ArrayList<>());
        Set<String> seen = new HashSet<>();
        for (Ran r : steps == null ? List.<Ran>of() : steps) {
            if (r == null || r.id() == null || r.id().contains("/") || onFailure.contains(r.id()) || !seen.add(r.id())
                    || "skipped".equals(r.status()) || "pending".equals(r.status())) {
                continue;
            }
            out.computeIfAbsent(lineOf.getOrDefault(r.id(), MAIN), k -> new ArrayList<>()).add(new Taken(r.id(), call(byId.get(r.id()), r.input())));
        }
        return out;
    }

    private static void lines(List<Step> steps, String line, boolean failure, Map<String, String> lineOf, Set<String> onFailure, Map<String, Step> byId) {
        for (Step s : steps == null ? List.<Step>of() : steps) {
            lineOf.put(s.id(), line);
            byId.put(s.id(), s);
            if (failure) {
                onFailure.add(s.id());
            }
            if (s.branches() != null) {
                for (int i = 0; i < s.branches().size(); i++) {
                    lines(s.branches().get(i), s.id() + "#" + i, failure, lineOf, onFailure, byId);
                }
            }
            if (s.cases() != null) {
                s.cases().forEach(c -> lines(c.steps(), line, failure, lineOf, onFailure, byId));
            }
            lines(s.children(), line, failure, lineOf, onFailure, byId);
            if (s.catchBlock() != null) {
                lines(s.catchBlock().steps(), line, true, lineOf, onFailure, byId);
            }
            lines(s.compensate(), line, true, lineOf, onFailure, byId);
        }
    }

    /** {@code <provider> <action>} of a provider step, as it ran (else as written); null for every other step. */
    private static String call(Step s, JsonNode input) {
        if (s == null || s.type() != StepType.PROVIDER) {
            return null;
        }
        String provider = text(input, "provider", s.withText("provider"));
        String action = text(input, "action", s.withText("action"));
        return provider + " " + action;
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode v = node == null ? null : node.get(field);
        return v != null && v.isValueNode() && !v.asText().isBlank() ? v.asText() : fallback;
    }

    /**
     * The first place {@code actual} parts from {@code expected}, line by line (the main line,
     * then the branches by name). When {@code expected} is {@code complete} the lines must be
     * equal; otherwise it is a job that stopped early (failed or cancelled), and each of its lines
     * only has to be the start of the same line in {@code actual}.
     */
    public static Optional<Difference> compare(Map<String, List<Taken>> expected, Map<String, List<Taken>> actual, boolean complete, String expectedLabel,
            String actualLabel) {
        Set<String> keys = new TreeSet<>(expected.keySet());
        if (complete) {
            keys.addAll(actual.keySet());
        }
        List<String> order = new ArrayList<>();
        order.add(MAIN);
        keys.stream().filter(k -> !MAIN.equals(k)).forEach(order::add);
        for (String key : order) {
            List<Taken> e = expected.getOrDefault(key, List.of());
            List<Taken> a = actual.getOrDefault(key, List.of());
            int common = Math.min(e.size(), a.size());
            for (int i = 0; i < common; i++) {
                if (!e.get(i).equals(a.get(i))) {
                    return Optional.of(new Difference(e.get(i).step(), expectedLabel + " ran " + e.get(i).describe() + " where " + actualLabel + " runs "
                            + a.get(i).describe()));
                }
            }
            if (e.size() > a.size()) {
                String after = common == 0 ? "" : " after " + a.get(common - 1).step();
                return Optional.of(new Difference(e.get(common).step(), expectedLabel + " ran " + e.get(common).describe() + "; " + actualLabel
                        + " does not run it" + after));
            }
            if (complete && a.size() > e.size()) {
                String after = common == 0 ? "first" : "after " + e.get(common - 1).step();
                return Optional.of(new Difference(a.get(common).step(), actualLabel + " runs " + a.get(common).describe() + " " + after + "; " + expectedLabel
                        + " did not"));
            }
        }
        return Optional.empty();
    }
}
