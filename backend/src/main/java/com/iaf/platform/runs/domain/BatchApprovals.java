package com.iaf.platform.runs.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.iaf.platform.common.ports.PolicyPort.PolicyDecision;
import com.iaf.platform.common.ports.PolicyPort.Rule;
import com.iaf.platform.workflows.domain.Step;
import com.iaf.platform.workflows.domain.StepType;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowDefinition.ApprovalPolicy;

/**
 * The one approval of a whole batch, the same on both engines. A batch asks for it when the
 * workflow's policy block requires an approval, or when the policy engine answered
 * {@code needs-approval} for any of its rows (the batch carries the strictest of its rows'
 * decisions, {@link #strictest}); its rows then run pre-approved. The group is the policy
 * block's, else the group of the workflow's first approval step, else the owning team.
 */
public final class BatchApprovals {

    public static final String DENY = "deny";
    public static final String NEEDS_APPROVAL = "needs-approval";
    public static final String ALLOW = "allow";

    private BatchApprovals() {
    }

    /** Whether the batch must be approved before its rows run. */
    public static boolean required(Job batch) {
        WorkflowDefinition def = batch.definition();
        boolean byPolicy = def != null && def.policy() != null && def.policy().approval() != null && def.policy().approval().required();
        return byPolicy || (batch.policy() != null && batch.policy().needsApproval());
    }

    /** Who approves the batch. */
    public static String group(WorkflowDefinition def) {
        ApprovalPolicy ap = def == null || def.policy() == null ? null : def.policy().approval();
        if (ap != null && ap.group() != null && !ap.group().isBlank()) {
            return ap.group();
        }
        if (def != null) {
            for (Step s : def.allSteps()) {
                if (s.type() == StepType.APPROVAL) {
                    String group = s.withText("group");
                    if (group != null && !group.isBlank() && !group.contains("{{")) {
                        return group;
                    }
                }
            }
            if (def.metadata() != null && def.metadata().owner() != null && !def.metadata().owner().isBlank()) {
                return def.metadata().owner();
            }
        }
        return "approvers";
    }

    /** The approval's timeout as written: the policy block's, else the default. */
    public static String timeout(WorkflowDefinition def) {
        ApprovalPolicy ap = def == null || def.policy() == null ? null : def.policy().approval();
        return ap == null || ap.timeout() == null || ap.timeout().isBlank() ? Durations.format(WorkflowInterpreter.DEFAULT_APPROVAL_TIMEOUT) : ap.timeout();
    }

    /** How strict a decision is: deny, then needs-approval, then allow. */
    public static int rank(PolicyDecision d) {
        String decision = d == null || d.decision() == null ? ALLOW : d.decision();
        return switch (decision) {
            case DENY -> 2;
            case NEEDS_APPROVAL -> 1;
            default -> 0;
        };
    }

    /**
     * The decision of a batch from its rows' decisions: the strictest one, carrying every
     * distinct rule the engine applied to any row. An empty list is an allow.
     */
    public static PolicyDecision strictest(List<PolicyDecision> rows, String engine) {
        PolicyDecision top = null;
        Set<Rule> rules = new LinkedHashSet<>();
        for (PolicyDecision d : rows) {
            if (d == null) {
                continue;
            }
            if (top == null || rank(d) > rank(top)) {
                top = d;
            }
            if (d.rules() != null) {
                rules.addAll(d.rules());
            }
        }
        if (top == null) {
            return PolicyDecision.allow(engine);
        }
        return new PolicyDecision(top.decision(), new ArrayList<>(rules), top.engine() == null ? engine : top.engine());
    }
}
