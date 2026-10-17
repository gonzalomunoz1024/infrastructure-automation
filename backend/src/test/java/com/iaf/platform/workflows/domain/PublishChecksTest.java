package com.iaf.platform.workflows.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.iaf.platform.common.domain.Problem;
import com.iaf.platform.common.ports.PolicyPort.PolicyDecision;
import com.iaf.platform.common.ports.PolicyPort.Rule;
import com.iaf.platform.workflows.SampleFixtures;
import com.iaf.platform.workflows.domain.ChangeRequest.Check;

class PublishChecksTest {

    static CheckContext ctx() {
        return SampleContentTest.context();
    }

    @Test
    void ansibleLintIsSkippedWithoutAnsibleSteps() {
        WorkflowDefinition d = WorkflowYaml.parse(SampleFixtures.yaml("workflows/team-compute/create-vm.yaml"));
        assertThat(PublishChecks.ansibleLint(d, ctx()).status()).isEqualTo("skipped");
    }

    @Test
    void ansibleLintPassesWhenEveryPlaybookIsPinned() {
        WorkflowDefinition d = WorkflowYaml.parse(SampleFixtures.yaml("workflows/team-compute/vm-restart.yaml"));
        Check c = PublishChecks.ansibleLint(d, ctx());
        assertThat(c.status()).isEqualTo("passed");
        assertThat(c.detail()).contains("1 playbook(s)");
    }

    @Test
    void ansibleLintFailsOnAnUnpinnedGitPlaybookAndPassesOnAPinnedLibrary() {
        String unpinned = SampleFixtures.yaml("workflows/team-compute/vm-restart.yaml").replaceAll("\\s+commit: 4e1d9a2c7b0f4e1d9a2c7b0f4e1d9a2c7b0f4e1d", "");
        Check failed = PublishChecks.ansibleLint(WorkflowYaml.parse(unpinned), ctx());
        assertThat(failed.status()).isEqualTo("failed");
        assertThat(failed.detail()).contains("restart");

        String library = """
                apiVersion: iaf.platform/v1
                kind: Workflow
                metadata: { name: restart-thing, title: Restart, noun: thing, verb: restart, owner: team-platform, version: 1.0.0 }
                workflow:
                  do:
                    - restart:
                        call: ansible
                        with: { playbook: ops-playbooks/vm.restart, inventory: dynamic }
                        retry: { maxAttempts: 1 }
                """;
        assertThat(PublishChecks.ansibleLint(WorkflowYaml.parse(library), ctx()).status()).as("the provider pins a commit").isEqualTo("passed");
        assertThat(PublishChecks.ansibleLint(WorkflowYaml.parse(library.replace("ops-playbooks/", "unknown/")), ctx()).status()).isEqualTo("failed");
    }

    @Test
    void policyAndReplayAndDiffChecksReportTheirDetail() {
        assertThat(PublishChecks.policy(PolicyDecision.allow("mock")).status()).isEqualTo("passed");
        Check denied = PublishChecks.policy(new PolicyDecision("deny", List.of(new Rule("no-prod-friday", "deny", "No prod changes on Friday")), "opa"));
        assertThat(denied.status()).isEqualTo("failed");
        assertThat(denied.detail()).contains("opa says deny").contains("no-prod-friday");

        assertThat(PublishChecks.replay(ReplayResult.none(), null).status()).isEqualTo("skipped");
        assertThat(PublishChecks.replay(new ReplayResult(true, 3, List.of()), "1.2.0").detail()).isEqualTo("3 job(s) of 1.2.0 replayed");
        Check diverged = PublishChecks.replay(new ReplayResult(false, 2, List.of(new ReplayResult.Divergence("job_1", "restart", "boom"))), "1.2.0");
        assertThat(diverged.status()).isEqualTo("failed");
        assertThat(diverged.detail()).contains("job_1 at restart: boom");

        assertThat(PublishChecks.diff("", null).detail()).isEqualTo("new workflow, 0 lines");
        assertThat(PublishChecks.diff("--- a/x\n+++ b/x\n@@ -1 +1 @@\n-a\n+b\n", "1.0.0").detail()).isEqualTo("+1 -1 against 1.0.0");
    }

    @Test
    void schemaChecksAndYamlRoundTripJudgeTheRecord() {
        WorkflowDefinition d = WorkflowYaml.parse(SampleFixtures.yaml("workflows/team-compute/vm-restart.yaml"));
        WorkflowRecord r = WorkflowRecord.of(d, WorkflowStatus.DRAFT, "workflows/team-compute/vm-restart.yaml", Instant.EPOCH, "t");
        assertThat(PublishChecks.schema(d).status()).isEqualTo("passed");
        assertThat(PublishChecks.yaml(r).status()).isEqualTo("passed");
        Check lint = PublishChecks.checks(List.of(Problem.warning("x", "s", null, "careful")));
        assertThat(lint.status()).isEqualTo("passed");
        assertThat(lint.detail()).startsWith("0 errors, 1 warnings");
        assertThat(PublishChecks.checks(List.of(Problem.error("y", null, null, "bad"))).status()).isEqualTo("failed");
        assertThat(PublishChecks.failed(List.of(lint, PublishChecks.replay(ReplayResult.none(), null)))).isEmpty();
    }
}
