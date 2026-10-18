package com.iaf.platform.runs.application;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.ports.PolicyPort;
import com.iaf.platform.common.ports.PolicyPort.PolicyDecision;
import com.iaf.platform.common.ports.PolicyPort.PolicyInput;

/**
 * What the policy engine is asked before a job runs, the same for a start through the API, a
 * batch row and the child of a {@code run} step: the {@code invoke} phase with the requester,
 * the workflow and version, the input, and a context of {@code channel}, {@code environment},
 * {@code parentId} (a child) and the workflow's own policy block ({@code changeRequired},
 * {@code approvalRequired}).
 */
public final class InvokePolicy {

    public static final String DENY = "deny";
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private InvokePolicy() {
    }

    public static PolicyInput input(Identity who, com.iaf.platform.workflows.domain.WorkflowDefinition def, String version, JsonNode input,
            String channel, String parentId) {
        ObjectNode context = F.objectNode();
        context.put("channel", channel);
        context.put("environment", JobFactory.environmentOf(input));
        if (parentId != null) {
            context.put("parentId", parentId);
        }
        if (def.policy() != null) {
            context.put("changeRequired", def.policy().changeRequired());
            if (def.policy().approval() != null) {
                context.put("approvalRequired", def.policy().approval().required());
            }
        }
        return new PolicyInput(PolicyPort.Phase.INVOKE, who, def.name(), version, input, context);
    }

    public static boolean denied(PolicyDecision decision) {
        return decision != null && DENY.equals(decision.decision());
    }

    /** The messages of the rules that denied, for the refusal. */
    public static List<String> denials(PolicyDecision decision) {
        return decision == null || decision.rules() == null ? List.of() : decision.rules().stream()
                .filter(r -> DENY.equals(r.result()))
                .map(PolicyPort.Rule::message)
                .filter(m -> m != null && !m.isBlank())
                .toList();
    }

    /** "Policy denies running <workflow>: <why>". */
    public static String refusal(String workflow, PolicyDecision decision) {
        List<String> why = denials(decision);
        return "Policy denies running " + workflow + (why.isEmpty() ? "" : ": " + String.join("; ", why));
    }
}
