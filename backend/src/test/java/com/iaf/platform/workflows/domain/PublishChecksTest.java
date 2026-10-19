package com.iaf.platform.workflows.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

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
    void theSchemaCheckJudgesTheStoredFileAndCompilesApiInput() {
        Check passed = PublishChecks.schema(WorkflowYaml.parse(SampleFixtures.yaml("workflows/team-compute/vm-restart.yaml")));
        assertThat(passed.status()).isEqualTo("passed");
        assertThat(passed.detail()).startsWith("kind Workflow, ");

        String badInput = """
                apiVersion: iaf.platform/v1
                kind: Workflow
                metadata: { name: vm-info, title: VM info, noun: vm, verb: read, owner: team-compute, version: 1.0.0 }
                api:
                  input:
                    type: object
                    required: vmId
                    properties:
                      vmId: { type: string }
                      image: IMAGE
                policy: { rbac: [ops] }
                workflow:
                  do:
                    - say: { notify: { channel: "#ops", message: hi } }
                """;
        Check refused = PublishChecks.schema(WorkflowYaml.parse(badInput.replace("IMAGE", "{ $ref: \"https://169.254.169.254/latest/meta-data\" }")));
        assertThat(refused.status()).isEqualTo("failed");
        assertThat(refused.detail()).startsWith("api.input refers to https://169.254.169.254/latest/meta-data, outside itself");

        Check notCompiled = PublishChecks.schema(WorkflowYaml.parse(badInput.replace("IMAGE", "{ type: string }")));
        assertThat(notCompiled.status()).isEqualTo("failed");
        assertThat(notCompiled.detail()).isEqualTo("api.input is not a valid JSON Schema: required: string found, array expected");
    }

    @Test
    void thePlaybooksCheckIsSkippedWithoutAnsibleSteps() {
        WorkflowDefinition d = WorkflowYaml.parse(SampleFixtures.yaml("workflows/team-compute/create-vm.yaml"));
        assertThat(PublishChecks.playbooks(d, ctx())).extracting(Check::id, Check::label, Check::status).containsExactly("playbooks", "Playbooks", "skipped");
    }

    @Test
    void thePlaybooksCheckPassesWhenEveryPlaybookIsPinned() {
        WorkflowDefinition d = WorkflowYaml.parse(SampleFixtures.yaml("workflows/team-compute/vm-restart.yaml"));
        Check c = PublishChecks.playbooks(d, ctx());
        assertThat(c.status()).isEqualTo("passed");
        assertThat(c.detail()).isEqualTo("1 playbook(s) pinned to a commit");
    }

    @Test
    void thePlaybooksCheckFailsOnAnUnpinnedGitPlaybookAndPassesOnAPinnedLibrary() {
        String unpinned = SampleFixtures.yaml("workflows/team-compute/vm-restart.yaml").replaceAll("\\s+commit: 4e1d9a2c7b0f4e1d9a2c7b0f4e1d9a2c7b0f4e1d", "");
        Check failed = PublishChecks.playbooks(WorkflowYaml.parse(unpinned), ctx());
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
        assertThat(PublishChecks.playbooks(WorkflowYaml.parse(library), ctx()).status()).as("the provider pins a commit").isEqualTo("passed");
        assertThat(PublishChecks.playbooks(WorkflowYaml.parse(library.replace("ops-playbooks/", "unknown/")), ctx()).status()).isEqualTo("failed");
    }

    @Test
    void thePlaybooksCheckNamesThePinsMadeAtPublishAndFailsWithTheReasonOfOneThatWasNot() {
        WorkflowDefinition pinned = WorkflowYaml.parse(SampleFixtures.yaml("workflows/team-compute/vm-restart.yaml"));
        String commit = "4e1d9a2c7b0f4e1d9a2c7b0f4e1d9a2c7b0f4e1d";

        Check passed = PublishChecks.playbooks(pinned, ctx(), new PlaybookPins.Outcome(Map.of("restart", commit), List.of()), PlaybookPins.Lint.NONE);
        assertThat(passed.status()).isEqualTo("passed");
        assertThat(passed.detail()).isEqualTo("1 playbook(s) pinned to a commit; pinned at publish: restart to 4e1d9a2c7b0f");

        Check failed = PublishChecks.playbooks(pinned, ctx(), new PlaybookPins.Outcome(Map.of(),
                List.of("step restart: v3.1.0 of github.com/acme/ops-playbooks did not resolve (connect timed out)")), PlaybookPins.Lint.NONE);
        assertThat(failed.status()).isEqualTo("failed");
        assertThat(failed.detail()).isEqualTo("could not pin to a commit: step restart: v3.1.0 of github.com/acme/ops-playbooks did not resolve (connect timed out)");

        assertThat(PublishChecks.playbooks(pinned, ctx(), PlaybookPins.Outcome.NONE, PlaybookPins.Lint.NONE)).isEqualTo(PublishChecks.playbooks(pinned, ctx()));
    }

    @Test
    void thePlaybooksCheckFailsWithTheLintersFindingsAndCountsWhatItLinted() {
        WorkflowDefinition pinned = WorkflowYaml.parse(SampleFixtures.yaml("workflows/team-compute/vm-restart.yaml"));
        Check passed = PublishChecks.playbooks(pinned, ctx(), new PlaybookPins.Outcome(Map.of("restart", "4e1d9a2c7b0f4e1d9a2c7b0f4e1d9a2c7b0f4e1d"), List.of()),
                new PlaybookPins.Lint(List.of("restart"), List.of()));
        assertThat(passed.status()).isEqualTo("passed");
        assertThat(passed.detail()).as("the pins stay last, where the change request page reads them")
                .isEqualTo("1 playbook(s) pinned to a commit, 1 linted; pinned at publish: restart to 4e1d9a2c7b0f");

        String finding = "step restart (github.com/acme/ops-playbooks@4e1d9a2c7b0f vm/restart.yml): vm/restart.yml:3: yaml[truthy]: Truthy value should be one of [false, true]\n"
                + "vm/restart.yml:9: name[missing]: All tasks should be named\n";
        Check failed = PublishChecks.playbooks(pinned, ctx(), PlaybookPins.Outcome.NONE, new PlaybookPins.Lint(List.of("restart"), List.of(finding)));
        assertThat(failed.status()).isEqualTo("failed");
        assertThat(failed.detail()).isEqualTo("the linter found problems: step restart (github.com/acme/ops-playbooks@4e1d9a2c7b0f vm/restart.yml): "
                + "vm/restart.yml:3: yaml[truthy]: Truthy value should be one of [false, true] | vm/restart.yml:9: name[missing]: All tasks should be named");

        Check cut = PublishChecks.playbooks(pinned, ctx(), PlaybookPins.Outcome.NONE, new PlaybookPins.Lint(List.of("restart"), List.of("x".repeat(5000))));
        assertThat(cut.detail()).as("a long output is cut").hasSize("the linter found problems: ".length() + 2000).endsWith("x…");
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
        Check passed = PublishChecks.playbooks(WorkflowYaml.parse(upload(DIGEST)), ctx());
        assertThat(passed.status()).isEqualTo("passed");
        assertThat(passed.detail()).isEqualTo("1 playbook(s) pinned to a commit");

        Check failed = PublishChecks.playbooks(WorkflowYaml.parse(upload("SHA256:9F2C0B7E")), ctx());
        assertThat(failed.status()).isEqualTo("failed");
        assertThat(failed.detail()).isEqualTo("upload digest is not 64 hex digits (playbook.digest): deploy");

        // the designer checks already say playbook.digest; :validate does not add publish.playbooks on top
        assertThat(WorkflowChecks.check(upload("SHA256:9F2C0B7E"), ctx())).extracting(Problem::code).containsExactly("playbook.digest");
        assertThat(PublishChecks.problems(WorkflowYaml.parse(upload("SHA256:9F2C0B7E")), ctx(), DependencyRulesTest.samples(), null, graph()))
                .extracting(Problem::code).doesNotContain("publish.playbooks");
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

    @Test
    void anMcpToolAnotherPublishedWorkflowHasIsARegistryErrorNamingTheToolAndTheWorkflow() {
        String restartYaml = SampleFixtures.yaml("workflows/team-compute/vm-restart.yaml");
        List<WorkflowDefinition> published = DependencyRulesTest.samples();
        assertThat(PublishChecks.registryProblems(WorkflowYaml.parse(restartYaml), published)).as("its own published version").isEmpty();

        WorkflowDefinition bounce = WorkflowYaml.parse(restartYaml.replace("name: vm-restart", "name: vm-bounce"));
        assertThat(PublishChecks.registryProblems(bounce, published)).extracting(Problem::level, Problem::code, Problem::field, Problem::message)
                .containsExactly(tuple(Problem.Level.ERROR, "mcp.toolName", "mcp.expose", "MCP tool vm_restart is already exposed by workflow vm-restart; give this workflow another noun or verb, or set mcp.expose to false"));
        // its tool against another workflow's batch tool
        WorkflowDefinition batch = WorkflowYaml.parse(restartYaml.replace("name: vm-restart", "name: vm-bounce").replace("verb: restart", "verb: restart-batch"));
        assertThat(PublishChecks.mcpToolNames(batch, published)).extracting(Problem::message)
                .containsExactly("MCP tool vm_restart_batch is already exposed by workflow vm-restart; give this workflow another noun or verb, or set mcp.expose to false");
        // a workflow that is no tool takes no tool name
        WorkflowDefinition hidden = WorkflowYaml.parse(restartYaml.replace("name: vm-restart", "name: vm-bounce").replace("  expose: true", "  expose: false"));
        assertThat(PublishChecks.mcpToolNames(hidden, published)).isEmpty();
    }

    @Test
    void aCycleThroughThePublishedWorkflowsIsARegistryError() {
        List<WorkflowDefinition> published = DependencyRulesTest.samples();
        WorkflowDefinition restart = WorkflowYaml.parse(SampleFixtures.yaml("workflows/team-compute/vm-restart.yaml"));
        assertThat(PublishChecks.runCycles(restart, published)).isEmpty();
        WorkflowDefinition looping = WorkflowYaml.parse(SampleFixtures.yaml("packages/workflow-checks/fixtures/run-cycle.yaml"));
        assertThat(PublishChecks.registryProblems(looping, published)).extracting(Problem::code, Problem::step, Problem::field, Problem::message)
                .containsExactly(tuple("run.cycle", "patchFirst", "workflow", "Runs go round in a cycle: vm-restart -> patch-cve -> vm-restart; a job would wait for itself and never finish"));
        // patch-cve as it would be published without its run step breaks the loop
        List<WorkflowDefinition> withoutRun = published.stream().filter(d -> !d.name().equals("patch-cve")).toList();
        assertThat(PublishChecks.runCycles(looping, withoutRun)).isEmpty();
    }

    @Test
    void aRegistryErrorReplacesTheDesignersWarningOfTheSameCodeAndIsListedOnce() {
        Problem warning = Problem.warning("mcp.toolName", null, "mcp.expose", "older");
        Problem error = Problem.error("mcp.toolName", null, "mcp.expose", "now");
        Problem other = Problem.warning("metadata.title", null, "metadata.title", "title");
        Problem same = Problem.error("run.self", "child", "workflow", "A workflow cannot run itself");
        assertThat(PublishChecks.merge(List.of(other, warning, same), List.of(error, same))).containsExactly(other, same, error);
        assertThat(PublishChecks.merge(List.of(other, warning), List.of())).containsExactly(other, warning);
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
        // the invoke, batch and preview routes all clash, and so does the MCP tool
        assertThat(problems).extracting(Problem::code).containsExactly("publish.playbooks", "publish.routes", "publish.routes", "publish.routes", "mcp.toolName");
        assertThat(problems.get(0).step()).isEqualTo("restart");
        assertThat(problems.get(1).message()).contains("is served by vm-restart");
    }

    static ChangeRequest providerChange(String id, String version, ChangeRequest.Status status) {
        return new ChangeRequest(id, ChangeRequest.Kind.PROVIDER, "vsphere", version, "change/provider-vsphere-" + version, "Provider vSphere " + version, "mateo",
                status, List.of(), "canary", "", "", Instant.parse("2026-10-17T10:00:00Z"), null, null, null);
    }

    @Test
    void aRequiredProviderChangeIsDoneOnceItsVersionOrALaterOneServesCalls() {
        Map<String, ChangeRequest> crs = Map.of(
                "cr_open", providerChange("cr_open", "1.4.0", ChangeRequest.Status.OPEN),
                "cr_merged", providerChange("cr_merged", "1.4.0", ChangeRequest.Status.MERGED),
                "cr_closed", providerChange("cr_closed", "1.4.0", ChangeRequest.Status.CLOSED));
        Function<String, Optional<ChangeRequest>> lookup = id -> Optional.ofNullable(crs.get(id));

        assertThat(PublishChecks.Required.of(List.of("cr_open", "cr_merged", "cr_closed", "cr_gone"), lookup, name -> Optional.of("1.3.0")))
                .extracting(PublishChecks.Required::standing, PublishChecks.Required::why)
                .containsExactly(
                        tuple(PublishChecks.Standing.OPEN, "cr_open (provider vsphere 1.4.0) is open"),
                        tuple(PublishChecks.Standing.ROLLING_OUT, "cr_merged (provider vsphere 1.4.0) is merged, but vsphere 1.3.0 still serves calls"),
                        tuple(PublishChecks.Standing.CLOSED, "cr_closed (provider vsphere 1.4.0) is closed"),
                        tuple(PublishChecks.Standing.CLOSED, "cr_gone is gone"));
        for (String current : List.of("1.4.0", "1.4.1", "2.0.0")) {
            assertThat(PublishChecks.Required.of(List.of("cr_merged"), lookup, name -> Optional.of(current)).get(0).standing())
                    .as(current).isEqualTo(PublishChecks.Standing.DONE);
        }
        for (String current : List.of("1.3.9", "1.4.0-rc.1")) {
            assertThat(PublishChecks.Required.of(List.of("cr_merged"), lookup, name -> Optional.of(current)).get(0).standing())
                    .as(current).isEqualTo(PublishChecks.Standing.ROLLING_OUT);
        }
        assertThat(PublishChecks.requires(List.of("cr_merged"), lookup, name -> Optional.empty()))
                .extracting(Check::status, Check::detail)
                .containsExactly("pending", "waiting for cr_merged (provider vsphere 1.4.0) to serve calls: it is merged, but vsphere is not published yet");
        assertThat(PublishChecks.requires(List.of("cr_open", "cr_merged"), lookup, name -> Optional.of("1.4.0")))
                .extracting(Check::status, Check::detail)
                .containsExactly("pending", "waiting for cr_open (provider vsphere 1.4.0) to merge; published: cr_merged (provider vsphere 1.4.0)");
    }
}
