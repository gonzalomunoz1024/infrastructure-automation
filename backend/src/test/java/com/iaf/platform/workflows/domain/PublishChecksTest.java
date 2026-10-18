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
    void ansibleLintNamesThePinsMadeAtPublishAndFailsWithTheReasonOfOneThatWasNot() {
        WorkflowDefinition pinned = WorkflowYaml.parse(SampleFixtures.yaml("workflows/team-compute/vm-restart.yaml"));
        String commit = "4e1d9a2c7b0f4e1d9a2c7b0f4e1d9a2c7b0f4e1d";

        Check passed = PublishChecks.ansibleLint(pinned, ctx(), new PlaybookPins.Outcome(java.util.Map.of("restart", commit), List.of()));
        assertThat(passed.status()).isEqualTo("passed");
        assertThat(passed.detail()).isEqualTo("1 playbook(s) pinned to a commit; pinned at publish: restart to 4e1d9a2c7b0f");

        Check failed = PublishChecks.ansibleLint(pinned, ctx(), new PlaybookPins.Outcome(java.util.Map.of(),
                List.of("step restart: v3.1.0 of github.com/acme/ops-playbooks did not resolve (connect timed out)")));
        assertThat(failed.status()).isEqualTo("failed");
        assertThat(failed.detail()).isEqualTo("could not pin to a commit: step restart: v3.1.0 of github.com/acme/ops-playbooks did not resolve (connect timed out)");

        assertThat(PublishChecks.ansibleLint(pinned, ctx(), PlaybookPins.Outcome.NONE)).isEqualTo(PublishChecks.ansibleLint(pinned, ctx()));
    }

    static final String DIGEST = "9f2c0b7e1d4a9f2c0b7e1d4a9f2c0b7e1d4a9f2c0b7e1d4a9f2c0b7e1d4a9f2c";

    static String upload(String sha256) {
        return """
                apiVersion: iaf.platform/v1
                kind: Workflow
                metadata: { name: site-deploy, title: Site deploy, noun: site, verb: deploy, owner: team-platform, version: 1.0.0 }
                policy:
                  rbac: [ops]
                workflow:
                  do:
                    - deploy:
                        call: ansible
                        with:
                          playbook: { upload: { file: site.zip, sha256: "%s", path: site.yml } }
                          inventory: dynamic
                        retry: { maxAttempts: 1 }
                """.formatted(sha256);
    }

    @Test
    void anUploadIsPinnedByADigestOf64HexDigitsAndNothingElse() {
        Check passed = PublishChecks.ansibleLint(WorkflowYaml.parse(upload(DIGEST)), ctx());
        assertThat(passed.status()).isEqualTo("passed");
        assertThat(passed.detail()).isEqualTo("1 playbook(s) pinned to a commit");

        Check failed = PublishChecks.ansibleLint(WorkflowYaml.parse(upload("SHA256:9F2C0B7E")), ctx());
        assertThat(failed.status()).isEqualTo("failed");
        assertThat(failed.detail()).isEqualTo("upload digest is not 64 hex digits (playbook.digest): deploy");

        // the designer checks already say playbook.digest; :validate does not add publish.ansibleLint on top
        assertThat(WorkflowChecks.check(upload("SHA256:9F2C0B7E"), ctx())).extracting(Problem::code).containsExactly("playbook.digest");
        assertThat(PublishChecks.problems(WorkflowYaml.parse(upload("SHA256:9F2C0B7E")), ctx(), DependencyRulesTest.samples(), null, graph()))
                .extracting(Problem::code).doesNotContain("publish.ansibleLint");
        assertThat(WorkflowChecks.check(upload(DIGEST), ctx())).isEmpty();
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
    @Test
    void aPathThatEndsInAParameterBatchesOnTheCollection() {
        assertThat(WorkflowYaml.parse(SampleFixtures.yaml("workflows/team-compute/vm-restart.yaml")).batchPath()).isEqualTo("/api/v1/vms:restart:batch");
        assertThat(WorkflowYaml.parse(SampleFixtures.yaml("workflows/team-compute/delete-vm.yaml")).batchPath()).isEqualTo("/api/v1/vms:delete:batch");
        assertThat(WorkflowYaml.parse(SampleFixtures.yaml("workflows/team-compute/create-vm.yaml")).batchPath()).isEqualTo("/api/v1/vms:batch");
        WorkflowDefinition nested = WorkflowYaml.parse("""
                apiVersion: iaf.platform/v1
                kind: Workflow
                metadata: { name: node-drain, noun: node, verb: drain, owner: team-compute, version: 1.0.0 }
                api: { path: "/api/v1/clusters/{clusterId}/nodes/{nodeId}:drain" }
                workflow:
                  do:
                    - noop:
                        set: { x: 1 }
                """);
        // the parent parameter stays in the path and applies to every row
        assertThat(nested.batchPath()).isEqualTo("/api/v1/clusters/{clusterId}/nodes:drain:batch");
    }

    @Test
    void routesFailWhenAnotherPublishedWorkflowAnswersTheSamePath() {
        WorkflowDefinition restart = WorkflowYaml.parse(SampleFixtures.yaml("workflows/team-compute/vm-restart.yaml"));
        WorkflowDefinition createVm = WorkflowYaml.parse(SampleFixtures.yaml("workflows/team-compute/create-vm.yaml"));
        assertThat(PublishChecks.routes(restart, List.of(restart, createVm)).status()).isEqualTo("passed");
        WorkflowDefinition copy = WorkflowYaml.parse(SampleFixtures.yaml("workflows/team-compute/vm-restart.yaml")
                .replace("name: vm-restart", "name: vm-reboot").replace("{vmId}", "{id}"));
        Check clash = PublishChecks.routes(copy, List.of(restart, createVm));
        assertThat(clash.status()).isEqualTo("failed");
        assertThat(clash.detail()).contains("served by vm-restart").contains(":restart:batch");
    }

    // ------------------------------------------------------------------ contracts

    static final String CREATE_VM = SampleFixtures.yaml("workflows/team-compute/create-vm.yaml");
    static final String WITHOUT_OWNER = CREATE_VM.replace("      owner:\n        type: string\n        title: Owner team\n", "");

    static DependencyRules.Graph graph() {
        return DependencyRules.of(DependencyRulesTest.samples(), DependencyRulesTest.sampleProducers());
    }

    @Test
    void contractsAreSkippedForAFirstVersionAndPassWithoutABreakingChange() {
        WorkflowDefinition current = WorkflowYaml.parse(CREATE_VM);
        assertThat(PublishChecks.contracts(current, null, graph()).status()).isEqualTo("skipped");
        Check same = PublishChecks.contracts(current.withVersion("2.0.2"), current, graph());
        assertThat(same.status()).isEqualTo("passed");
        assertThat(same.detail()).startsWith("no breaking change against 2.0.1; consumers: ").contains("web-environment (run ^2 at vm)");
    }

    @Test
    void aBreakingChangeWithoutAMajorVersionFailsAndListsEveryAffectedConsumer() {
        WorkflowDefinition current = WorkflowYaml.parse(CREATE_VM);
        Check c = PublishChecks.contracts(WorkflowYaml.parse(WITHOUT_OWNER).withVersion("2.1.0"), current, graph());
        assertThat(c.id()).isEqualTo("contracts");
        assertThat(c.status()).isEqualTo("failed");
        assertThat(c.detail()).startsWith("2.1.0 breaks the contract of 2.0.1 without a new major version: input 'owner' was removed. Affected consumers: ")
                .contains("web-environment (run ^2 at vm)");
    }

    @Test
    void aBreakingChangeWithAMajorVersionPassesAndListsWhoStaysOnTheOldMajor() {
        WorkflowDefinition current = WorkflowYaml.parse(CREATE_VM);
        Check c = PublishChecks.contracts(WorkflowYaml.parse(WITHOUT_OWNER).withVersion("3.0.0"), current, graph());
        assertThat(c.status()).isEqualTo("passed");
        assertThat(c.detail()).startsWith("new major version 3.0.0 with breaking changes against 2.0.1: input 'owner' was removed.")
                .endsWith("Staying on the old major: web-environment (run ^2 at vm)");
    }

    @Test
    void validateProblemsNameTheChangeRequestChecksAFileWouldFail() {
        WorkflowDefinition current = WorkflowYaml.parse(CREATE_VM);
        List<WorkflowDefinition> published = DependencyRulesTest.samples();
        assertThat(PublishChecks.problems(current.withVersion("2.0.2"), ctx(), published, current, graph())).isEmpty();

        List<Problem> breaking = PublishChecks.problems(WorkflowYaml.parse(WITHOUT_OWNER).withVersion("2.0.2"), ctx(), published, current, graph());
        assertThat(breaking).singleElement().satisfies(p -> {
            assertThat(p.code()).isEqualTo("publish.contracts");
            assertThat(p.level()).isEqualTo(Problem.Level.ERROR);
            assertThat(p.field()).isEqualTo("metadata.version");
            assertThat(p.fix().value()).isEqualTo("3.0.0");
        });

        String unpinned = SampleFixtures.yaml("workflows/team-compute/vm-restart.yaml").replaceAll("\\s+commit: 4e1d9a2c7b0f4e1d9a2c7b0f4e1d9a2c7b0f4e1d", "")
                .replace("name: vm-restart", "name: vm-reboot").replace("{vmId}", "{id}");
        List<Problem> problems = PublishChecks.problems(WorkflowYaml.parse(unpinned), ctx(), published, null, graph());
        // the invoke, batch and preview routes all clash
        assertThat(problems).extracting(Problem::code).containsExactly("publish.ansibleLint", "publish.routes", "publish.routes", "publish.routes");
        assertThat(problems.get(0).step()).isEqualTo("restart");
        assertThat(problems.get(1).message()).contains("is served by vm-restart");
    }
}
