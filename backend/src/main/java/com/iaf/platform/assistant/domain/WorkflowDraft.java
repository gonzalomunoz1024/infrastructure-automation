package com.iaf.platform.assistant.domain;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The structured output of a drafting session, the shape of {@code devin/workflow-draft.schema.json}:
 * what the {@code draft} event carries and what the canvas loads.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowDraft(
        String status,
        String summary,
        String workflowYaml,
        List<String> changes,
        List<String> assumptions,
        List<Question> questions,
        List<Missing> missing,
        Validation validation,
        DryRun dryRun) {

    public static final String WORKING = "working";
    public static final String READY = "ready";
    public static final String NEEDS_INPUT = "needs_input";
    public static final String FAILED = "failed";

    private static final ObjectMapper MAPPER = new ObjectMapper().setDefaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL));

    /** A question only the user can answer, with up to four options. */
    public record Question(String question, List<String> options) {
    }

    /** A building block the flow needs that the registry does not have: provider_action, workflow, resource_type, playbook, credential. */
    public record Missing(String kind, String name, String why) {
    }

    public record Validation(boolean valid, int rounds, List<String> errors) {
    }

    public record DryRun(boolean passed, List<String> path, String error) {
    }

    public JsonNode toJson() {
        return MAPPER.valueToTree(this);
    }

    public static WorkflowDraft fromJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return MAPPER.convertValue(node, WorkflowDraft.class);
    }

    /** A failed draft with one error line, for when nothing better could be produced. */
    public static WorkflowDraft failed(String summary, String error) {
        return new WorkflowDraft(FAILED, summary, "", List.of(), List.of(), List.of(), List.of(), new Validation(false, 0, List.of(error)), null);
    }

    public boolean isFinal() {
        return READY.equals(status) || NEEDS_INPUT.equals(status) || FAILED.equals(status) || "done".equals(status);
    }
}
