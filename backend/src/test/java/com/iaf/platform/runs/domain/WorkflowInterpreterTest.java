package com.iaf.platform.runs.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.runs.SampleContent;
import com.iaf.platform.runs.domain.JobStep.StepStatus;
import com.iaf.platform.workflows.domain.Step;
import com.iaf.platform.workflows.domain.WorkflowDefinition;

/** The walker against the real sample workflows, on the recorded sample world. */
class WorkflowInterpreterTest {

    private static final Instant T0 = Instant.parse("2026-10-17T10:00:00Z");

    static JobContext context(WorkflowDefinition d, Map<String, Object> input) {
        JsonNode in = InputDefaults.apply(d.inputSchema(), SampleContent.json(input));
        String env = in.path("environment").asText("dev");
        return new JobContext("job_test", d.name(), d.version(), in, SampleContent.GONZALO, env, T0, "trace_1", null);
    }

    static Outcome run(String workflow, Map<String, Object> input, SampleStepExecutor executor) {
        WorkflowDefinition d = SampleContent.workflow(workflow);
        return new WorkflowInterpreter(d, executor, context(d, input)).run();
    }

    static List<String> ids(SampleStepExecutor ex, String jobId, StepStatus status) {
        return ex.events(jobId).stream().filter(e -> e.status() == status).map(StepEvent::id).toList();
    }

    static StepEvent last(SampleStepExecutor ex, String jobId, String stepId, StepStatus status) {
        return ex.events(jobId).stream().filter(e -> e.id().equals(stepId) && e.status() == status).reduce((a, b) -> b).orElseThrow();
    }

    // ---------------------------------------------------------------- vm-restart

    @Test
    void vmRestartHappyPathWithAnAutoApprovingExecutor() {
        SampleStepExecutor ex = SampleContent.executor();
        Outcome o = run("vm-restart", Map.of("vmId", "vm-1042", "reason", "stuck"), ex);

        assertThat(o.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(o.output().path("vmName").asText()).isEqualTo("web-1042");
        assertThat(o.output().path("powerState").asText()).isEqualTo("poweredOn");
        assertThat(ids(ex, "job_test", StepStatus.SUCCEEDED))
                .containsExactly("checkVmExists", "noBackupRunning", "approval", "restart", "validate", "notifyDone");
        assertThat(ids(ex, "job_test", StepStatus.WAITING)).containsExactly("approval");
        assertThat(ex.approvals().values()).singleElement().satisfies(a -> {
            assertThat(a.group()).isEqualTo("compute-approvers");
            assertThat(a.message()).isEqualTo("Restart web-1042 (poweredOn) in dev? Reason: stuck");
            assertThat(a.escalation().to()).isEqualTo("compute-leads");
        });
        assertThat(last(ex, "job_test", "notifyDone", StepStatus.SUCCEEDED).output().path("message").asText())
                .isEqualTo("Restarted web-1042 for gonzalo: poweredOn");
        assertThat(last(ex, "job_test", "restart", StepStatus.SUCCEEDED).output().path("changed").asInt()).isEqualTo(2);
        assertThat(ex.calls()).extracting(SampleStepExecutor.Call::provider, SampleStepExecutor.Call::action)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("vsphere", "vm.get"), org.assertj.core.groups.Tuple.tuple("backup", "jobs.list"),
                        org.assertj.core.groups.Tuple.tuple("ops-playbooks", "vm.restart"), org.assertj.core.groups.Tuple.tuple("vsphere", "vm.waitForPowerState"));
    }

    @Test
    void anAssertionFailureFailsTheStep() {
        SampleStepExecutor ex = SampleContent.executor();
        // the backup provider's sample has no running jobs; a planned failure stands in for a busy backup
        ex.failAt("noBackupRunning", "assertion_failed", "backup running", false);
        Outcome o = run("vm-restart", Map.of("vmId", "vm-1042"), ex);
        assertThat(o.status()).isEqualTo(JobStatus.FAILED);
        assertThat(o.failedStep()).isEqualTo("noBackupRunning");
        assertThat(ex.attentions()).hasSize(1);
        // the workflow-level catch ran with $error bound
        assertThat(last(ex, "job_test", "notifyOwner", StepStatus.SUCCEEDED).output().path("message").asText())
                .isEqualTo("VM restart job_test failed at noBackupRunning: backup running (aborted by nobody)");
    }

    // ---------------------------------------------------------------- create-vm

    @Test
    void createVmNestedSwitchPicksTheRightSpec() {
        SampleStepExecutor ex = SampleContent.executor();
        Outcome o = run("create-vm", Map.of("name", "web-01", "image", "img-77", "size", "large"), ex);

        assertThat(o.status()).isEqualTo(JobStatus.SUCCEEDED);
        JsonNode createInput = last(ex, "job_test", "createVm", StepStatus.SUCCEEDED).input();
        assertThat(createInput.path("input").path("cpu").asInt()).isEqualTo(8);
        assertThat(createInput.path("input").path("memoryGb").asInt()).isEqualTo(32);
        assertThat(createInput.path("input").path("environment").asText()).isEqualTo("dev");
        assertThat(ids(ex, "job_test", StepStatus.SUCCEEDED)).contains("largeSpec").doesNotContain("mediumSpec", "smallSpec");
        assertThat(o.output().path("vmId").asText()).startsWith("vm-");
        assertThat(o.output().path("name").asText()).isEqualTo("web-01");
        JsonNode vm = ex.resource("vm", o.output().path("vmId").asText());
        assertThat(vm.path("attributes").path("cpu").asInt()).isEqualTo(8);
        assertThat(vm.path("attributes").path("owner").asText()).isEqualTo("gonzalo");
        assertThat(vm.path("builtFrom").get(0).asText()).isEqualTo("img-77");

        SampleStepExecutor medium = SampleContent.executor();
        run("create-vm", Map.of("name", "web-02", "image", "img-77", "size", "medium"), medium);
        assertThat(last(medium, "job_test", "createVm", StepStatus.SUCCEEDED).input().path("input").path("cpu").asInt()).isEqualTo(4);

        SampleStepExecutor small = SampleContent.executor();
        run("create-vm", Map.of("name", "web-03", "image", "img-77", "size", "small"), small);
        assertThat(last(small, "job_test", "createVm", StepStatus.SUCCEEDED).input().path("input").path("cpu").asInt()).isEqualTo(2);
    }

    @Test
    void createVmCompensatesTheHalfMadeVmWhenWaitReadyFails() {
        SampleStepExecutor ex = SampleContent.executor();
        ex.failAt("waitReady", "timeout", "guest tools never came up", true);
        Outcome o = run("create-vm", Map.of("name", "web-01", "image", "img-77", "size", "small"), ex);

        assertThat(o.status()).isEqualTo(JobStatus.ROLLED_BACK);
        assertThat(o.failedStep()).isEqualTo("waitReady");
        assertThat(o.errorClass()).isEqualTo("timeout");
        String vmId = last(ex, "job_test", "createVm", StepStatus.SUCCEEDED).output().path("id").asText();
        StepEvent del = last(ex, "job_test", "deleteHalfMade", StepStatus.SUCCEEDED);
        assertThat(del.input().path("input").path("id").asText()).isEqualTo(vmId);
        assertThat(del.output().path("verified").asBoolean()).isTrue();
        assertThat(ids(ex, "job_test", StepStatus.COMPENSATED)).containsExactly("createVm");
        assertThat(ex.world().isDeleted("vsphere", vmId)).isTrue();
        assertThat(ids(ex, "job_test", StepStatus.SUCCEEDED)).doesNotContain("registerVm");
    }

    // ---------------------------------------------------------------- mongodb-upgrade

    @Test
    void mongodbUpgradeEndsTheFlowWhenAlreadyOnTarget() {
        SampleStepExecutor ex = SampleContent.executor();
        Outcome o = run("mongodb-upgrade", Map.of("clusterId", "db-9", "targetVersion", "7.0"), ex);

        assertThat(o.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(ids(ex, "job_test", StepStatus.SUCCEEDED)).containsExactly("getCluster", "alreadyThere");
        assertThat(ex.approvals()).isEmpty();
        assertThat(o.output().path("version").isNull()).isTrue();
    }

    @Test
    void mongodbUpgradeReadsBackTheNewVersionAfterTheUpgrade() {
        SampleStepExecutor ex = SampleContent.executor();
        Outcome o = run("mongodb-upgrade", Map.of("clusterId", "db-9", "targetVersion", "8.0", "maintenanceWindow", "Sat 02:00"), ex);

        assertThat(o.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(o.output().path("version").asText()).isEqualTo("8.0");
        assertThat(o.output().path("snapshotId").asText()).startsWith("snapshot-");
        assertThat(ex.approvals().values()).singleElement().satisfies(a ->
                assertThat(a.message()).isEqualTo("Upgrade orders-9 from 7.0 to 8.0 during Sat 02:00?"));
        assertThat(ids(ex, "job_test", StepStatus.SUCCEEDED)).contains("setFcv", "rollingUpgrade", "waitHealthy", "upgrade", "verify", "registerVersion");
        assertThat(ex.resource("database", "db-9").path("attributes").path("version").asText()).isEqualTo("8.0");
    }

    @Test
    void mongodbUpgradeRestoresTheSnapshotWhenTheUpgradeFails() {
        SampleStepExecutor ex = SampleContent.executor();
        ex.failAt("rollingUpgrade", "unavailable", "Ops Manager answered 503", true);
        Outcome o = run("mongodb-upgrade", Map.of("clusterId", "db-9", "targetVersion", "8.0"), ex);

        assertThat(o.status()).isEqualTo(JobStatus.FAILED);
        assertThat(o.errorClass()).isEqualTo("UpgradeFailed");
        assertThat(o.failedStep()).isEqualTo("failLoudly");
        assertThat(o.message()).isEqualTo("Upgrade failed and the snapshot was restored: Ops Manager answered 503");
        StepEvent restore = last(ex, "job_test", "restore", StepStatus.SUCCEEDED);
        assertThat(restore.input().path("input").path("snapshotId").asText()).startsWith("snapshot-");
        assertThat(ex.attentions()).as("failures inside try go to catch, never to a person").isEmpty();
        assertThat(last(ex, "job_test", "pageDba", StepStatus.SUCCEEDED).output().path("message").asText())
                .contains("MongoDB upgrade job_test on db-9 failed: Upgrade failed and the snapshot was restored");
        assertThat(ids(ex, "job_test", StepStatus.SUCCEEDED)).doesNotContain("verify", "registerVersion");
    }

    // ---------------------------------------------------------------- web-environment

    @Test
    void webEnvironmentForksLoopsAndRunsChildren() {
        SampleStepExecutor ex = SampleContent.executor();
        Outcome o = run("web-environment", Map.of("name", "shop", "environment", "dev", "image", "img-1", "vmCount", 2), ex);

        assertThat(o.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(o.output().path("url").asText()).isEqualTo("https://shop.example.com");
        assertThat(o.output().path("databaseId").asText()).startsWith("db-");
        assertThat(ex.childRuns()).hasSize(3);
        assertThat(ex.childRuns()).extracting(r -> r.definition().name()).containsExactlyInAnyOrder("create-database", "create-vm", "create-vm");
        assertThat(ex.childRuns()).allSatisfy(r -> assertThat(r.outcome().succeeded()).isTrue());
        StepEvent loop = last(ex, "job_test", "webServers", StepStatus.SUCCEEDED);
        assertThat(loop.items()).isEqualTo(2);
        assertThat(loop.output().path("items")).hasSize(2);
        assertThat(loop.output().path("items").get(0).path("outputs").path("name").asText()).isEqualTo("shop-web-1");
        assertThat(loop.output().path("items").get(1).path("outputs").path("name").asText()).isEqualTo("shop-web-2");
        StepEvent lb = last(ex, "job_test", "loadBalancer", StepStatus.SUCCEEDED);
        assertThat(lb.input().path("vars").path("targets")).hasSize(2);
        StepEvent env = last(ex, "job_test", "registerEnv", StepStatus.SUCCEEDED);
        assertThat(env.input().path("builtFrom")).hasSize(3);
        assertThat(env.output().path("attributes").path("webServers")).hasSize(2);
        // the child create-database skipped its approval (dev) by jumping to create
        String dbJob = ex.childRuns().stream().filter(r -> r.definition().name().equals("create-database")).findFirst().orElseThrow().jobId();
        assertThat(ids(ex, dbJob, StepStatus.SKIPPED)).containsExactly("approval");
    }

    @Test
    void aFailingChildFailsTheParentStep() {
        SampleStepExecutor ex = SampleContent.executor();
        ex.failAt("waitReady", "timeout", "cluster never became healthy", false);
        ex.attender(a -> Optional.of(AttentionDecision.abort("ana")));
        Outcome o = run("web-environment", Map.of("name", "shop", "environment", "dev", "image", "img-1", "vmCount", 1), ex);
        assertThat(o.status()).isEqualTo(JobStatus.FAILED);
        assertThat(o.failedStep()).as("the failure names the run step inside the fork").isEqualTo("database");
        assertThat(o.errorClass()).isEqualTo("timeout");
        assertThat(ids(ex, "job_test", StepStatus.FAILED)).contains("database", "build");
    }

    // ---------------------------------------------------------------- patch-cve

    @Test
    void patchCveRunsTheScriptAndJumpsToTheReboot() {
        SampleStepExecutor ex = SampleContent.executor();
        Outcome o = run("patch-cve", Map.of("vmId", "vm-1042", "cve", "CVE-2026-1234"), ex);

        assertThat(o.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(o.output().path("fixed").asBoolean()).isTrue();
        assertThat(o.output().path("rebooted").asBoolean()).isTrue();
        assertThat(ids(ex, "job_test", StepStatus.SKIPPED)).containsExactly("approval");
        assertThat(ids(ex, "job_test", StepStatus.SUCCEEDED)).containsExactly("useVm", "needsApproval", "patch", "rebootIfNeeded", "reboot", "verify");
        StepEvent verify = last(ex, "job_test", "verify", StepStatus.SUCCEEDED);
        assertThat(verify.output().path("installed").asInt()).isEqualTo(2);
        assertThat(ex.childRuns()).singleElement().satisfies(r -> {
            assertThat(r.definition().name()).isEqualTo("vm-restart");
            assertThat(r.input().path("reason").asText()).isEqualTo("Reboot after CVE-2026-1234");
        });
    }

    @Test
    void patchCveSkipsTheRebootWhenThePatchDoesNotNeedIt() {
        SampleStepExecutor ex = SampleContent.executor();
        // the ops-playbooks sample says a reboot is required; make the ansible answer say otherwise through check mode on test
        Outcome o = run("patch-cve", Map.of("vmId", "vm-1042", "cve", "CVE-2026-9999", "severity", "low"), ex);
        assertThat(o.status()).isEqualTo(JobStatus.FAILED);
        // CVE-2026-9999 is not in the sample packages: the verify assertion fails
        assertThat(o.failedStep()).isEqualTo("verify");
        assertThat(o.errorClass()).isEqualTo("assertion_failed");
    }

    // ---------------------------------------------------------------- delete-vm

    @Test
    void deleteVmVerifiesTheDeleteAndRetiresTheVm() {
        SampleStepExecutor ex = SampleContent.executor();
        Outcome o = run("delete-vm", Map.of("vmId", "vm-7", "reason", "decommissioned after migration"), ex);

        assertThat(o.status()).isEqualTo(JobStatus.SUCCEEDED);
        StepEvent del = last(ex, "job_test", "delete", StepStatus.SUCCEEDED);
        assertThat(del.output().path("deleted").asBoolean()).isTrue();
        assertThat(del.output().path("verified").asBoolean()).isTrue();
        assertThat(ex.world().isDeleted("vsphere", "vm-7")).isTrue();
        assertThat(o.output().path("snapshotId").asText()).startsWith("snap-");
        assertThat(ex.resource("vm", "vm-7").path("status").asText()).isEqualTo("retired");
        assertThat(last(ex, "job_test", "snapshot", StepStatus.SUCCEEDED).input().path("input").path("keepDays").asInt()).isEqualTo(7);

        // the world remembers: a later read of the same id is a not_found
        SampleStepExecutor again = ex;
        WorkflowDefinition restart = SampleContent.workflow("vm-restart");
        Outcome o2 = new WorkflowInterpreter(restart, again, context(restart, Map.of("vmId", "vm-7"))).run();
        assertThat(o2.status()).isEqualTo(JobStatus.FAILED);
        assertThat(o2.errorClass()).isEqualTo("not_found");
        assertThat(o2.failedStep()).isEqualTo("checkVmExists");
    }

    // ---------------------------------------------------------------- pause on failure

    @Test
    void pauseOnFailureRetriesTheStepWhenAsked() {
        SampleStepExecutor ex = SampleContent.executor();
        ex.failAt("validate", "timeout", "still powered off", true);
        ex.attender(a -> Optional.of(AttentionDecision.retry("gonzalo")));
        Outcome o = run("vm-restart", Map.of("vmId", "vm-1042"), ex);

        assertThat(o.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(ex.attentions()).singleElement().satisfies(a -> {
            assertThat(a.step()).isEqualTo("validate");
            assertThat(a.error().errorClass()).isEqualTo("timeout");
            assertThat(a.options()).containsExactly("retry", "edit-input", "skip", "abort");
        });
        assertThat(last(ex, "job_test", "validate", StepStatus.FAILED).attempt()).isEqualTo(1);
        assertThat(last(ex, "job_test", "validate", StepStatus.SUCCEEDED).attempt()).isEqualTo(1);
        assertThat(o.output().path("powerState").asText()).isEqualTo("poweredOn");
    }

    @Test
    void pauseOnFailureRetriesWithAnEditedInput() {
        SampleStepExecutor ex = SampleContent.executor();
        ex.failAt("validate", "timeout", "still powered off", true);
        ex.attender(a -> Optional.of(AttentionDecision.editInput(SampleContent.json(Map.of("input", Map.of("timeout", 1800))), "gonzalo")));
        Outcome o = run("vm-restart", Map.of("vmId", "vm-1042"), ex);
        assertThat(o.status()).isEqualTo(JobStatus.SUCCEEDED);
        StepEvent ok = last(ex, "job_test", "validate", StepStatus.SUCCEEDED);
        assertThat(ok.input().path("input").path("timeout").asInt()).isEqualTo(1800);
        assertThat(ok.input().path("input").path("id").asText()).isEqualTo("vm-1042");
    }

    @Test
    void pauseOnFailureSkipsTheStepWhenAsked() {
        SampleStepExecutor ex = SampleContent.executor();
        ex.failAt("validate", "timeout", "still powered off", true);
        ex.attender(a -> Optional.of(AttentionDecision.skip("gonzalo")));
        Outcome o = run("vm-restart", Map.of("vmId", "vm-1042"), ex);
        assertThat(o.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(ids(ex, "job_test", StepStatus.SKIPPED)).containsExactly("validate");
        assertThat(ids(ex, "job_test", StepStatus.SUCCEEDED)).contains("notifyDone");
        assertThat(o.output().path("powerState").isNull()).isTrue();
    }

    @Test
    void pauseOnFailureAbortsAndRunsTheCatchFlow() {
        SampleStepExecutor ex = SampleContent.executor();
        ex.failAt("validate", "timeout", "still powered off", true);
        ex.attender(a -> Optional.of(AttentionDecision.abort("gonzalo")));
        Outcome o = run("vm-restart", Map.of("vmId", "vm-1042"), ex);
        assertThat(o.status()).isEqualTo(JobStatus.FAILED);
        assertThat(o.failedStep()).isEqualTo("validate");
        assertThat(o.errorClass()).isEqualTo("timeout");
        assertThat(ids(ex, "job_test", StepStatus.SUCCEEDED)).contains("notifyOwner").doesNotContain("notifyDone");
    }

    @Test
    void nobodyDecidingWithinTheAttentionTimeoutAborts() {
        SampleStepExecutor ex = SampleContent.executor();
        ex.failAt("validate", "timeout", "still powered off", true);
        ex.attender(a -> Optional.empty());
        Outcome o = run("vm-restart", Map.of("vmId", "vm-1042"), ex);
        assertThat(o.status()).isEqualTo(JobStatus.FAILED);
        assertThat(o.message()).contains("aborted by timeout");
    }

    @Test
    void pauseOnFailureFalseFailsFast() {
        SampleStepExecutor ex = SampleContent.executor();
        WorkflowDefinition d = SampleContent.workflow("vm-restart");
        Step validate = d.step("validate").orElseThrow();
        Step fast = new Step(validate.id(), validate.name(), validate.type(), validate.with(), validate.output(), validate.assertion(), validate.retry(),
                validate.timeout(), false, null, null, null, null, null, null, null);
        List<Step> steps = d.steps().stream().map(s -> s.id().equals("validate") ? fast : s).toList();
        WorkflowDefinition fastDef = d.withFlow(new WorkflowDefinition.Flow(steps, d.workflow().catchFlow(), d.workflow().outputs()));
        ex.failAt("validate", "timeout", "still powered off", true);
        Outcome o = new WorkflowInterpreter(fastDef, ex, context(fastDef, Map.of("vmId", "vm-1042"))).run();
        assertThat(o.status()).isEqualTo(JobStatus.FAILED);
        assertThat(ex.attentions()).isEmpty();
    }

    // ---------------------------------------------------------------- retries, approvals, cancel

    @Test
    void retriesWithExponentialBackoffBeforeGivingUp() {
        SampleStepExecutor ex = SampleContent.executor();
        ex.failAt("create", "rate_limited", "429 slow down", true, 1);
        Instant before = ex.now();
        Outcome o = run("create-database", Map.of("name", "orders", "environment", "dev"), ex);
        assertThat(o.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(last(ex, "job_test", "create", StepStatus.FAILED).attempt()).isEqualTo(1);
        assertThat(last(ex, "job_test", "create", StepStatus.SUCCEEDED).attempt()).isEqualTo(2);
        assertThat(ex.attentions()).isEmpty();
        assertThat(Duration.between(before, ex.now())).isGreaterThanOrEqualTo(Duration.ofSeconds(1));
        assertThat(o.output().path("secret").asText()).isEqualTo("vault:databases/orders");
    }

    @Test
    void aNonRetryableErrorIsNotRetried() {
        SampleStepExecutor ex = SampleContent.executor();
        ex.failAt("create", "not_found", "no such tier", false, 1);
        ex.attender(a -> Optional.of(AttentionDecision.abort("ana")));
        Outcome o = run("create-database", Map.of("name", "orders", "environment", "dev"), ex);
        assertThat(o.status()).isEqualTo(JobStatus.FAILED);
        assertThat(ex.events("job_test").stream().filter(e -> e.id().equals("create") && e.status() == StepStatus.FAILED)).hasSize(1);
    }

    @Test
    void aProductionDatabaseAsksForApprovalAndARejectionFailsTheJob() {
        SampleStepExecutor ex = SampleContent.executor();
        ex.approver(req -> Optional.of(ApprovalDecision.rejected("ana", "not this week", ex.now())));
        Outcome o = run("create-database", Map.of("name", "orders", "environment", "prod", "tier", "M30"), ex);
        assertThat(o.status()).isEqualTo(JobStatus.FAILED);
        assertThat(o.errorClass()).isEqualTo("rejected");
        assertThat(o.message()).isEqualTo("Rejected by ana: not this week");
        assertThat(ex.approvals().values()).singleElement().satisfies(a -> {
            assertThat(a.group()).isEqualTo("data-approvers");
            assertThat(a.message()).isEqualTo("Create production cluster orders (M30)?");
        });
        assertThat(ex.attentions()).isEmpty();
    }

    @Test
    void anApprovalNobodyDecidesExpires() {
        SampleStepExecutor ex = SampleContent.executor();
        ex.approver(req -> Optional.empty());
        Outcome o = run("vm-restart", Map.of("vmId", "vm-1042"), ex);
        assertThat(o.status()).isEqualTo(JobStatus.FAILED);
        assertThat(o.errorClass()).isEqualTo("expired");
        assertThat(ex.logs()).anyMatch(l -> l.contains("escalated") && l.contains("compute-leads"));
    }

    @Test
    void cancellingStopsTheWalkWithoutCompensation() {
        SampleStepExecutor ex = new SampleStepExecutor(SampleContent.providers(), SampleContent::child, SampleContent.types(), T0) {
            @Override
            public boolean cancelled(JobContext ctx) {
                return ctx.output("createVm") != null;
            }
        };
        Outcome o = run("create-vm", Map.of("name", "web-01", "image", "img-77", "size", "small"), ex);
        assertThat(o.status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(ids(ex, "job_test", StepStatus.SUCCEEDED)).doesNotContain("waitReady", "deleteHalfMade");
    }

    // ---------------------------------------------------------------- the other samples

    @Test
    void buildVmImageNamesTheImageFromTheStartDate() {
        SampleStepExecutor ex = SampleContent.executor();
        Outcome o = run("build-vm-image", Map.of("os", "ubuntu-24.04"), ex);
        assertThat(o.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(last(ex, "job_test", "imageName", StepStatus.SUCCEEDED).output().path("imageName").asText()).isEqualTo("ubuntu-24.04-cis-l1-2026-10-17");
        assertThat(o.output().path("imageId").asText()).startsWith("img-");
        assertThat(o.output().path("templateId").asText()).startsWith("tpl-");
        JsonNode image = ex.resource("image", o.output().path("imageId").asText());
        assertThat(image.path("status").asText()).isEqualTo("ready");
        assertThat(image.path("attributes").path("scanned").asBoolean()).isTrue();
        assertThat(image.path("attributes").path("os").asText()).isEqualTo("ubuntu-24.04");
    }

    @Test
    void buildVmImageMarksTheImageFailedThroughTheCatchFlow() {
        SampleStepExecutor ex = SampleContent.executor();
        ex.failAt("waitForBuild", "timeout", "packer never finished", true);
        ex.attender(a -> Optional.of(AttentionDecision.abort("gonzalo")));
        Outcome o = run("build-vm-image", Map.of("os", "rhel-9", "hardening", "cis-l2"), ex);
        assertThat(o.status()).isEqualTo(JobStatus.FAILED);
        String imageId = last(ex, "job_test", "startBuild", StepStatus.SUCCEEDED).output().path("imageId").asText();
        assertThat(ex.resource("image", imageId).path("status").asText()).isEqualTo("failed");
        assertThat(ex.resource("image", imageId).path("attributes").path("error").asText()).contains("packer never finished");
    }

    @Test
    void requestStorageAnswersTheIdpTileAndAttachesTheVolume() {
        SampleStepExecutor ex = SampleContent.executor();
        Outcome o = run("request-storage", Map.of("vmId", "vm-1042", "sizeGb", 200), ex);
        assertThat(o.status()).isEqualTo(JobStatus.SUCCEEDED);
        String volumeId = o.output().path("volumeId").asText();
        assertThat(volumeId).startsWith("vol-");
        assertThat(last(ex, "job_test", "requestVolume", StepStatus.SUCCEEDED).input().path("input").path("requester").asText()).isEqualTo("gonzalo");
        assertThat(last(ex, "job_test", "attach", StepStatus.SUCCEEDED).output().path("device").asText()).isEqualTo("/dev/sdb");
        assertThat(ex.resource("volume", volumeId).path("builtFrom").get(0).asText()).isEqualTo("vm-1042");
        assertThat(ex.resource("volume", volumeId).path("attributes").path("tier").asText()).isEqualTo("standard");
    }

    @Test
    void reserveIpAddressPublishesTheRecordOfTheAddressItReserved() {
        SampleStepExecutor ex = SampleContent.executor();
        Outcome o = run("reserve-ip-address", Map.of("pool", "lab", "hostname", "web-01"), ex);
        assertThat(o.status()).isEqualTo(JobStatus.SUCCEEDED);
        String address = o.output().path("address").asText();
        assertThat(address).matches("10\\.20\\.0\\.\\d+");
        assertThat(o.output().path("fqdn").asText()).isEqualTo("web-01.lab.example.internal");
        JsonNode record = last(ex, "job_test", "publishRecord", StepStatus.SUCCEEDED).input().path("input");
        assertThat(record.path("zone").asText()).isEqualTo("lab.example.internal");
        assertThat(record.path("value").asText()).isEqualTo(address);
        assertThat(record.path("ttl").isInt()).as("a whole-string expression keeps its type").isTrue();
        assertThat(record.path("ttl").asInt()).isEqualTo(300);
    }

    @Test
    void everySampleWorkflowRunsEndToEndOnTheSamples() {
        Map<String, Map<String, Object>> inputs = Map.of(
                "vm-restart", Map.of("vmId", "vm-1042"),
                "create-vm", Map.of("name", "web-01", "image", "img-1", "size", "small"),
                "delete-vm", Map.of("vmId", "vm-9", "reason", "decommissioned after migration"),
                "create-database", Map.of("name", "orders", "environment", "dev"),
                "mongodb-upgrade", Map.of("clusterId", "db-9", "targetVersion", "8.0"),
                "build-vm-image", Map.of("os", "ubuntu-24.04"),
                "patch-cve", Map.of("vmId", "vm-1042", "cve", "CVE-2026-1234"),
                "request-storage", Map.of("vmId", "vm-1042", "sizeGb", 100),
                "web-environment", Map.of("name", "shop", "environment", "test", "image", "img-1", "vmCount", 2),
                "reserve-ip-address", Map.of("pool", "lab", "hostname", "web-01"));
        assertThat(SampleContent.workflows()).hasSize(10);
        for (WorkflowDefinition d : SampleContent.workflows()) {
            SampleStepExecutor ex = SampleContent.executor();
            Outcome o = run(d.name(), inputs.get(d.name()), ex);
            assertThat(o.status()).as(d.name() + ": " + o.message()).isEqualTo(JobStatus.SUCCEEDED);
        }
    }
}
