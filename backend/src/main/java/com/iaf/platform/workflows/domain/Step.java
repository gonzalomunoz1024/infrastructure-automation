package com.iaf.platform.workflows.domain;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * One step of a workflow. {@code with} holds the step's own parameters as the YAML spells them;
 * container steps carry their children in {@code branches} (fork), {@code cases} (switch),
 * {@code children} (for, try) and {@code catchBlock} (try).
 */
public record Step(
        String id,
        String name,
        StepType type,
        JsonNode with,
        Output output,
        @JsonProperty("assert") String assertion,
        Retry retry,
        String timeout,
        boolean pauseOnFailure,
        Position position,
        String notes,
        List<List<Step>> branches,
        List<SwitchCase> cases,
        List<Step> children,
        @JsonProperty("catch") CatchBlock catchBlock,
        List<Step> compensate) {

    public record Output(String as) {
    }

    public record Retry(int maxAttempts, String backoff) {
        public static final Retry NONE = new Retry(1, "none");
    }

    public record Position(double x, double y) {
    }

    /**
     * One branch of a switch: {@code then} jumps to a step id (or {@code end}); {@code steps}
     * ({@code do:} in YAML) runs a nested branch and continues after the switch.
     */
    public record SwitchCase(String when, String then, @JsonProperty("default") boolean isDefault, @JsonProperty("do") List<Step> steps) {
    }

    public record CatchBlock(String as, List<Step> steps) {
    }

    public String displayName() {
        return name == null || name.isBlank() ? id : name;
    }

    /** The step and every step nested inside it, depth first. */
    public List<Step> flatten() {
        List<Step> out = new ArrayList<>();
        out.add(this);
        if (branches != null) {
            branches.forEach(b -> b.forEach(s -> out.addAll(s.flatten())));
        }
        if (cases != null) {
            cases.stream().filter(c -> c.steps() != null).forEach(c -> c.steps().forEach(s -> out.addAll(s.flatten())));
        }
        if (children != null) {
            children.forEach(s -> out.addAll(s.flatten()));
        }
        if (catchBlock != null && catchBlock.steps() != null) {
            catchBlock.steps().forEach(s -> out.addAll(s.flatten()));
        }
        if (compensate != null) {
            compensate.forEach(s -> out.addAll(s.flatten()));
        }
        return out;
    }

    public String withText(String field) {
        return with == null || !with.has(field) || with.get(field).isNull() ? null : with.get(field).asText();
    }

    public JsonNode withNode(String field) {
        return with == null ? null : with.get(field);
    }

    public Step withPosition(Position p) {
        return new Step(id, name, type, with, output, assertion, retry, timeout, pauseOnFailure, p, notes, branches, cases, children, catchBlock, compensate);
    }

    public Step withParams(JsonNode newWith) {
        return new Step(id, name, type, newWith, output, assertion, retry, timeout, pauseOnFailure, position, notes, branches, cases, children, catchBlock, compensate);
    }

    public static Step simple(String id, String name, StepType type, JsonNode with) {
        return new Step(id, name, type, with, null, null, null, null, true, null, null, null, null, null, null, null);
    }
}
