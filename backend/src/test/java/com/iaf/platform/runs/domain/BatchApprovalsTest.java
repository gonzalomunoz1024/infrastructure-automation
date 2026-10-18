package com.iaf.platform.runs.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.iaf.platform.common.ports.PolicyPort.PolicyDecision;
import com.iaf.platform.common.ports.PolicyPort.Rule;
import com.iaf.platform.runs.SampleContent;
import com.iaf.platform.runs.application.JobFactory;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowYaml;

/** The rule for a batch's one approval: when it is asked for, from whom, and which of the rows' decisions the batch keeps. */
class BatchApprovalsTest {

    private static final Rule PASS = new Rule("requester.team", "pass", "acts for a team");
    private static final Rule ASK = new Rule("prod", "needs-approval", "prod needs a sign-off");
    private static final Rule NO = new Rule("freeze", "deny", "frozen");

    private static WorkflowDefinition yaml(String policy) {
        return WorkflowYaml.parse("""
                apiVersion: iaf.platform/v1
                kind: Workflow
                metadata: { name: t, title: T, noun: thing, verb: touch, owner: team-x, version: 1.0.0 }
                """ + policy + """
                workflow:
                  do:
                    - ask:
                        approval: { group: step-approvers, message: "ok?" }
                """);
    }

    private static Job batch(WorkflowDefinition def, PolicyDecision decision) {
        return JobFactory.newBatch(def, "1.0.0", List.of(SampleContent.json(Map.of("id", "a"))), null, null, List.of(), SampleContent.GONZALO,
                "batch", null, decision, null, Instant.EPOCH);
    }

    @Test
    void theStrictestDecisionWinsAndEveryDistinctRuleIsKept() {
        PolicyDecision allow = new PolicyDecision("allow", List.of(PASS), "mock");
        PolicyDecision ask = new PolicyDecision("needs-approval", List.of(PASS, ASK), "mock");
        PolicyDecision deny = new PolicyDecision("deny", List.of(NO), "mock");

        assertThat(BatchApprovals.strictest(List.of(allow, ask, allow), "mock").decision()).isEqualTo("needs-approval");
        assertThat(BatchApprovals.strictest(List.of(allow, ask, allow), "mock").rules()).containsExactly(PASS, ASK);
        assertThat(BatchApprovals.strictest(List.of(ask, deny), "mock").decision()).isEqualTo("deny");
        assertThat(BatchApprovals.strictest(List.of(allow, allow), "mock").decision()).isEqualTo("allow");
        assertThat(BatchApprovals.strictest(List.of(), "mock")).isEqualTo(PolicyDecision.allow("mock"));
        assertThat(BatchApprovals.rank(deny)).isGreaterThan(BatchApprovals.rank(ask));
        assertThat(BatchApprovals.rank(ask)).isGreaterThan(BatchApprovals.rank(allow));
        assertThat(BatchApprovals.rank(null)).isZero();
    }

    @Test
    void anApprovalIsRequiredByThePolicyBlockOrByTheEnginesDecision() {
        WorkflowDefinition open = yaml("");
        WorkflowDefinition gated = yaml("policy:\n  approval: { required: true, group: policy-approvers, timeout: 2h }\n");

        assertThat(BatchApprovals.required(batch(open, PolicyDecision.allow("mock")))).isFalse();
        assertThat(BatchApprovals.required(batch(open, null))).isFalse();
        assertThat(BatchApprovals.required(batch(open, new PolicyDecision("needs-approval", List.of(ASK), "mock")))).isTrue();
        assertThat(BatchApprovals.required(batch(gated, PolicyDecision.allow("mock")))).isTrue();
    }

    @Test
    void theGroupIsThePolicyBlocksThenTheFirstApprovalStepsThenTheOwner() {
        assertThat(BatchApprovals.group(yaml("policy:\n  approval: { required: true, group: policy-approvers }\n"))).isEqualTo("policy-approvers");
        assertThat(BatchApprovals.group(yaml(""))).isEqualTo("step-approvers");
        assertThat(BatchApprovals.group(WorkflowYaml.parse("""
                apiVersion: iaf.platform/v1
                kind: Workflow
                metadata: { name: t, title: T, noun: thing, verb: touch, owner: team-x, version: 1.0.0 }
                workflow:
                  do:
                    - get:
                        call: provider
                        with: { provider: vsphere, action: vm.get }
                """))).isEqualTo("team-x");
        assertThat(BatchApprovals.timeout(yaml("policy:\n  approval: { required: true, group: g, timeout: 2h }\n"))).isEqualTo("2h");
        assertThat(BatchApprovals.timeout(yaml(""))).isEqualTo(Durations.format(WorkflowInterpreter.DEFAULT_APPROVAL_TIMEOUT));
    }
}
