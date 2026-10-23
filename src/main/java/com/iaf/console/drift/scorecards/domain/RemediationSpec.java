package com.iaf.console.drift.scorecards.domain;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A rule's one-click fix: {@code workflow} starts a catalog verb of the type (or a workflow by
 * name when its noun differs) with {@code input}; {@code tags} sets {@code set} through the state
 * service. Values may name {@code {{ $input.<name> }}} or {@code {{ $resource.<path> }}}.
 */
public record RemediationSpec(String title, String kind, String verb, String workflow, ObjectNode input, Map<String, String> set,
        List<RemediationInput> inputs) {

    public static final String WORKFLOW = "workflow";
    public static final String TAGS = "tags";

    public RemediationSpec {
        if (!WORKFLOW.equals(kind) && !TAGS.equals(kind)) {
            throw new IllegalArgumentException("a remediation's kind is workflow or tags, not " + kind);
        }
        if (WORKFLOW.equals(kind) && verb == null && workflow == null) {
            throw new IllegalArgumentException("a workflow remediation names a verb or a workflow");
        }
        if (TAGS.equals(kind) && (set == null || set.isEmpty())) {
            throw new IllegalArgumentException("a tags remediation names the tags it sets");
        }
        set = set == null ? Map.of() : Map.copyOf(new TreeMap<>(set));
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
    }

    public boolean tags() {
        return TAGS.equals(kind);
    }
}
