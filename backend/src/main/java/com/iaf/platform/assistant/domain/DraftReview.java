package com.iaf.platform.assistant.domain;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.domain.Problem;
import com.iaf.platform.runs.domain.DryRunResult;

/**
 * What the platform found when it checked an agent's draft itself: the problems of the workflow
 * checks against the live catalog, each on the step it concerns, and the dry run when the checks
 * pass. The draft the canvas loads carries this instead of the agent's own view of its validation.
 */
public record DraftReview(String yaml, List<Problem> problems, DryRunResult dryRun) {

    public DraftReview {
        problems = problems == null ? List.of() : List.copyOf(problems);
    }

    public boolean valid() {
        return problems.stream().noneMatch(Problem::isError);
    }

    /** Valid, and the dry run passed when it ran. */
    public boolean clean() {
        return valid() && (dryRun == null || dryRun.passed());
    }

    /** The problems, and a failed dry run as an error on the step where it stopped. */
    public List<Problem> findings() {
        List<Problem> out = new ArrayList<>(problems);
        if (dryRun != null && !dryRun.passed()) {
            out.add(Problem.error("dry-run.failed", failedStep(), null, dryRunError()));
        }
        return out;
    }

    public List<Problem> errors() {
        return findings().stream().filter(Problem::isError).toList();
    }

    private String failedStep() {
        if (dryRun.steps() != null) {
            for (DryRunResult.DryRunStep s : dryRun.steps()) {
                if ("failed".equals(s.status())) {
                    return s.id();
                }
            }
        }
        String error = dryRun.error();
        int colon = error == null ? -1 : error.indexOf(": ");
        return colon > 0 && !error.substring(0, colon).contains(" ") ? error.substring(0, colon) : null;
    }

    private String dryRunError() {
        String error = dryRun.error() == null || dryRun.error().isBlank() ? "the dry run failed" : dryRun.error();
        String step = failedStep();
        return step != null && error.startsWith(step + ": ") ? error.substring(step.length() + 2) : error;
    }

    /**
     * The agent's draft with the platform's validation and dry run. A draft the agent calls ready that
     * the platform does not pass goes back to working while rounds remain, and fails on the last one.
     */
    public JsonNode applyTo(JsonNode draft, int round, boolean lastRound) {
        ObjectNode out = draft != null && draft.isObject() ? ((ObjectNode) draft).deepCopy() : Events.mapper().createObjectNode();
        ObjectNode validation = out.putObject("validation");
        validation.put("valid", valid());
        validation.put("rounds", round);
        ArrayNode errors = validation.putArray("errors");
        errors().forEach(p -> errors.add(line(p)));
        if (dryRun == null) {
            out.remove("dryRun");
        } else {
            ObjectNode dry = out.putObject("dryRun");
            dry.put("passed", dryRun.passed());
            ArrayNode path = dry.putArray("path");
            (dryRun.path() == null ? List.<String>of() : dryRun.path()).forEach(path::add);
            if (!dryRun.passed()) {
                dry.put("error", Words.clip(dryRunError(), 300));
            }
        }
        if (WorkflowDraft.READY.equals(out.path("status").asText()) && !clean()) {
            out.put("status", lastRound ? WorkflowDraft.FAILED : WorkflowDraft.WORKING);
        }
        return out;
    }

    /** One finding as the draft's {@code validation.errors} lists it: {@code code @step: message}. */
    public static String line(Problem p) {
        return p.code() + (p.step() == null ? "" : " @" + p.step()) + (p.field() == null ? "" : " (" + p.field() + ")") + ": " + p.message();
    }

    /** The message that sends the errors back on the agent's session. */
    public String followUp(int round, int maxRounds) {
        StringBuilder sb = new StringBuilder();
        sb.append("PLATFORM_CHECKS (round ").append(round).append(" of ").append(maxRounds).append("):\n");
        sb.append("The platform checked the workflowYaml of your last structured output against the live registry")
                .append(valid() ? " and dry-ran it" : "")
                .append(", and it is not ready. Fix each finding below, update the structured output, and set status ready only when none is left. Change nothing else.\n");
        for (Problem p : errors()) {
            sb.append("- ").append(p.code());
            if (p.step() != null) {
                sb.append(" at step ").append(p.step());
            }
            if (p.field() != null) {
                sb.append(" (").append(p.field()).append(')');
            }
            sb.append(": ").append(p.message()).append('\n');
        }
        return sb.toString();
    }
}
