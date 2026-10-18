package com.iaf.platform.runs.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.runs.SampleContent;
import com.iaf.platform.runs.domain.JobStep.StepStatus;
import com.iaf.platform.workflows.domain.Step;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowYaml;

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

    @Test
    void aRunStepWithOnFailureContinueRecordsTheChildFailureAndGoesOn() {
        String yaml = SampleContent.yaml("web-environment").replace("""
                                    workflow: create-database
                                    version: "^1"
                """, """
                                    workflow: create-database
                                    version: "^1"
                                    onFailure: continue
                """);
        assertThat(yaml).contains("onFailure: continue");
        WorkflowDefinition d = com.iaf.platform.workflows.domain.WorkflowYaml.parse(yaml);
        SampleStepExecutor ex = SampleContent.executor();
        ex.failAt("waitReady", "timeout", "cluster never became healthy", false);
        ex.attender(a -> Optional.of(AttentionDecision.abort("ana")));

        Outcome o = new WorkflowInterpreter(d, ex, context(d, Map.of("name", "shop", "environment", "dev", "image", "img-1", "vmCount", 1))).run();

        StepEvent database = last(ex, "job_test", "database", StepStatus.SUCCEEDED);
        assertThat(database.output().path("status").asText()).isEqualTo("failed");
        assertThat(database.output().path("error").path("step").asText()).as("the child's step that failed").isEqualTo("waitReady");
        assertThat(database.output().path("error").path("class").asText()).isEqualTo("timeout");
        assertThat(database.output().path("jobId").asText()).isNotBlank();
        assertThat(ids(ex, "job_test", StepStatus.FAILED)).doesNotContain("database", "build");
        assertThat(ex.logs()).anyMatch(l -> l.contains("continuing (onFailure: continue)"));
        // the parent went on past the fork: what fails now is a later step that needs the database
        assertThat(ids(ex, "job_test", StepStatus.SUCCEEDED)).contains("build", "loadBalancer");
        assertThat(o.failedStep()).isNotEqualTo("database");
    }

    // ---------------------------------------------------------------- build it first (orCreateWith)

    static final Map<String, Object> NEW_IMAGE = Map.of("create", Map.of("os", "rhel-9"));

    @Test
    void createVmBuildsTheImageFirstWhenTheInputAsksForOne() {
        SampleStepExecutor ex = SampleContent.executor();
        WorkflowDefinition d = SampleContent.workflow("create-vm");
        JobContext ctx = context(d, Map.of("name", "web-01", "image", NEW_IMAGE, "size", "small"));

        Outcome o = new WorkflowInterpreter(d, ex, ctx).run();

        assertThat(o.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(ex.childRuns()).singleElement().satisfies(r -> {
            assertThat(r.definition().name()).isEqualTo("build-vm-image");
            assertThat(r.parentId()).isEqualTo("job_test");
            assertThat(r.input().path("os").asText()).isEqualTo("rhel-9");
            assertThat(r.input().path("hardening").asText()).as("the producer's own default").isEqualTo("cis-l1");
        });
        StepEvent build = last(ex, "job_test", "image.create", StepStatus.SUCCEEDED);
        assertThat(build.output().path("resources")).isNotEmpty();
        String imageId = build.output().path("resources").get(0).path("id").asText();
        assertThat(imageId).startsWith("img-");
        assertThat(ex.resource("image", imageId).path("status").asText()).isEqualTo("ready");
        assertThat(last(ex, "job_test", "useImage", StepStatus.SUCCEEDED).input().path("id").asText()).as("the job goes on with the new id").isEqualTo(imageId);
        assertThat(last(ex, "job_test", "registerVm", StepStatus.SUCCEEDED).output().path("attributes").path("image").asText()).isEqualTo(imageId);
        assertThat(ctx.input().path("image").asText()).isEqualTo(imageId);
        assertThat(ids(ex, "job_test", StepStatus.SUCCEEDED).get(0)).as("the build runs before the first step").isEqualTo("image.create");
        assertThat(ex.logs()).anyMatch(l -> l.contains("image: built image " + imageId + " with build-vm-image"));
    }

    @Test
    void anIdNeedsNoBuild() {
        SampleStepExecutor ex = SampleContent.executor();
        Outcome o = run("create-vm", Map.of("name", "web-01", "image", "img-77", "size", "small"), ex);
        assertThat(o.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(ex.childRuns()).isEmpty();
        assertThat(ex.events("job_test")).noneMatch(e -> e.id().equals("image.create"));
    }

    @Test
    void aFailedBuildFailsTheJobBeforeItsFirstStep() {
        SampleStepExecutor ex = SampleContent.executor();
        ex.failAt("waitForBuild", "timeout", "packer never finished", false);
        ex.attender(a -> Optional.of(AttentionDecision.abort("gonzalo")));

        Outcome o = run("create-vm", Map.of("name", "web-01", "image", NEW_IMAGE, "size", "small"), ex);

        assertThat(o.status()).isEqualTo(JobStatus.FAILED);
        assertThat(o.failedStep()).isEqualTo("image.create");
        assertThat(o.errorClass()).isEqualTo("timeout");
        assertThat(ex.events("job_test")).noneMatch(e -> e.id().equals("useImage"));
    }

    @Test
    void aProducerThatRegistersNoResourceOfTheTypeFailsTheJob() {
        String yaml = SampleContent.yaml("create-vm").replace("orCreateWith: build-vm-image", "orCreateWith: vm-restart");
        WorkflowDefinition d = com.iaf.platform.workflows.domain.WorkflowYaml.parse(yaml);
        SampleStepExecutor ex = SampleContent.executor();

        Outcome o = new WorkflowInterpreter(d, ex, context(d, Map.of("name", "web-01", "image",
                Map.of("create", Map.of("vmId", "vm-1042", "reason", "stuck")), "size", "small"))).run();

        assertThat(ex.childRuns()).singleElement().satisfies(r -> assertThat(r.outcome().succeeded()).isTrue());
        assertThat(o.status()).isEqualTo(JobStatus.FAILED);
        assertThat(o.failedStep()).isEqualTo("image.create");
        assertThat(o.errorClass()).isEqualTo("no_resource");
        assertThat(o.message()).isEqualTo("vm-restart registered no image");
    }

    @Test
    void aRerunReplaysTheRecordedBuildInsteadOfBuildingAgain() {
        WorkflowDefinition d = SampleContent.workflow("create-vm");
        JsonNode built = SampleContent.json(Map.of("jobId", "job_old-1", "status", "succeeded", "outputs", Map.of("imageId", "img-77"),
                "resources", List.of(Map.of("id", "img-77", "type", "image"))));
        List<JobStep> seed = List.of(
                JobStep.builder().id("image.create").status(StepStatus.SUCCEEDED).output(built).build(),
                JobStep.builder().id("useImage").status(StepStatus.SUCCEEDED)
                        .output(SampleContent.json(Map.of("id", "img-77", "type", "image", "attributes", Map.of("templateId", "tpl-77")))).build());
        SampleStepExecutor ex = SampleContent.executor();

        Outcome o = new WorkflowInterpreter(d, ex, context(d, Map.of("name", "web-01", "image", NEW_IMAGE, "size", "small")),
                WorkflowInterpreter.Resume.from("sizeToSpec", seed)).run();

        assertThat(o.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(ex.childRuns()).as("nothing is built twice").isEmpty();
        assertThat(ex.events("job_test")).filteredOn(e -> e.id().equals("image.create")).singleElement().satisfies(e -> assertThat(e.replayed()).isTrue());
        assertThat(last(ex, "job_test", "registerVm", StepStatus.SUCCEEDED).output().path("attributes").path("image").asText()).isEqualTo("img-77");
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
    void patchCveFailsTheVerifyWhenThePlaybookDidNotFixTheCve() {
        // the playbook ran but its packages carry another CVE than the one asked for
        SampleStepExecutor ex = new SampleStepExecutor(SampleContent.providers(), SampleContent::child, SampleContent.types(),
                Instant.parse("2026-10-17T10:00:00Z")) {
            @Override
            public JsonNode runAutomation(JobContext ctx, Step step, JsonNode with, Duration timeout) {
                JsonNode out = super.runAutomation(ctx, step, with, timeout);
                if (step.id().equals("patch")) {
                    out.path("packages").forEach(p -> ((ObjectNode) p).set("cves", SampleContent.json(List.of("CVE-2026-0001"))));
                }
                return out;
            }
        };
        Outcome o = run("patch-cve", Map.of("vmId", "vm-1042", "cve", "CVE-2026-9999", "severity", "low"), ex);
        assertThat(o.status()).isEqualTo(JobStatus.FAILED);
        assertThat(o.failedStep()).isEqualTo("verify");
        assertThat(o.errorClass()).isEqualTo("assertion_failed");
    }

    @Test
    void patchCveVerifiesTheCveItWasAskedFor() {
        SampleStepExecutor ex = SampleContent.executor();
        Outcome o = run("patch-cve", Map.of("vmId", "vm-1042", "cve", "CVE-2026-9999", "severity", "low"), ex);
        assertThat(o.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(o.output().path("fixed").asBoolean()).isTrue();
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

    // ---------------------------------------------------------------- the interpreter's rules

    /** A fork whose first branch waits inside a try while the second fails outside any. */
    static final String FORK_GUARD = """
            apiVersion: iaf.platform/v1
            kind: Workflow
            metadata: { name: t-fork-guard, title: Fork guard, noun: thing, verb: fork, owner: team-x, version: 1.0.0 }
            workflow:
              do:
                - both:
                    name: Both
                    fork:
                      branches:
                        - - guarded:
                              name: Guarded
                              try:
                                do:
                                  - pause:
                                      name: Pause
                                      wait: { duration: 10m }
                        - - broken:
                              name: Broken
                              call: provider
                              with: { provider: vsphere, action: vm.doesNotExist, input: { id: vm-1 } }
            """;

    /**
     * Runs a fork's first branch on a thread of its own until it sleeps, then the second branch on
     * the caller's thread, then lets the first go on: how an engine running branches at once can
     * interleave them, made deterministic.
     */
    static final class InterleavingExecutor extends SampleStepExecutor {
        private final CountDownLatch sleeping = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile Thread first;

        InterleavingExecutor() {
            super(SampleContent.providers(), SampleContent::child, SampleContent.types(), T0);
        }

        @Override
        public List<JsonNode> runBranches(JobContext ctx, Step step, List<Supplier<JsonNode>> branches) {
            CompletableFuture<JsonNode> a = CompletableFuture.supplyAsync(() -> {
                first = Thread.currentThread();
                return branches.get(0).get();
            });
            await(sleeping);
            RuntimeException failure = null;
            JsonNode b = null;
            try {
                b = branches.get(1).get();
            } catch (RuntimeException e) {
                failure = e;
            } finally {
                release.countDown();
            }
            List<JsonNode> out = new ArrayList<>();
            out.add(a.join());
            out.add(b);
            if (failure != null) {
                throw failure;
            }
            return out;
        }

        @Override
        public void sleep(JobContext ctx, Duration duration) {
            if (Thread.currentThread() == first) {
                sleeping.countDown();
                await(release);
            }
            super.sleep(ctx, duration);
        }

        private static void await(CountDownLatch latch) {
            try {
                if (!latch.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("the branches did not interleave");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    static Outcome run(String yaml, SampleStepExecutor executor, WorkflowInterpreter.Rules rules) {
        WorkflowDefinition d = WorkflowYaml.parse(yaml);
        return new WorkflowInterpreter(d, executor, context(d, Map.of()), null, rules).run();
    }

    @Test
    void aForkBranchOutsideAnyTryPausesForAttentionWhileItsSiblingWaitsInsideOne() {
        InterleavingExecutor ex = new InterleavingExecutor();
        Outcome o = run(FORK_GUARD, ex, WorkflowInterpreter.Rules.CURRENT);

        assertThat(ex.attentions()).singleElement().satisfies(a -> {
            assertThat(a.step()).isEqualTo("broken");
            assertThat(a.error().errorClass()).isEqualTo("unknown_action");
        });
        assertThat(o.status()).isEqualTo(JobStatus.FAILED);
        assertThat(o.failedStep()).isEqualTo("broken");
        assertThat(o.message()).endsWith("(aborted by nobody)");
        assertThat(ids(ex, "job_test", StepStatus.SUCCEEDED)).as("the sibling still ran to its end").contains("pause", "guarded");
    }

    @Test
    void theRecordedRulesLetASiblingInsideATryKeepAForkBranchFromPausing() {
        InterleavingExecutor ex = new InterleavingExecutor();
        Outcome o = run(FORK_GUARD, ex, WorkflowInterpreter.Rules.RECORDED_BEFORE_INTERPRETER_RULES);

        assertThat(ex.attentions()).as("one try depth for every branch, as recorded").isEmpty();
        assertThat(o.status()).isEqualTo(JobStatus.FAILED);
        assertThat(o.failedStep()).isEqualTo("broken");
        assertThat(o.message()).doesNotContain("aborted");
    }

    /** A malformed wait duration on a step that would retry. */
    static final String LEAF_ERROR = """
            apiVersion: iaf.platform/v1
            kind: Workflow
            metadata: { name: t-leaf-error, title: Leaf error, noun: thing, verb: wait, owner: team-x, version: 1.0.0 }
            workflow:
              do:
                - pause:
                    name: Pause
                    wait: { duration: soon }
                    retry: { maxAttempts: 2 }
                    pauseOnFailure: false
            """;

    @Test
    void aMalformedWaitDurationFailsTheStepAsInvalidInputWithoutARetry() {
        SampleStepExecutor ex = SampleContent.executor();
        Outcome o = run(LEAF_ERROR, ex, WorkflowInterpreter.Rules.CURRENT);

        assertThat(o.status()).isEqualTo(JobStatus.FAILED);
        assertThat(o.failedStep()).isEqualTo("pause");
        assertThat(o.errorClass()).isEqualTo("invalid_input");
        assertThat(o.message()).isEqualTo("'soon' is not a duration; use 30m, 4h or PT30M");
        assertThat(ids(ex, "job_test", StepStatus.FAILED)).as("bad input is never retried").containsExactly("pause");
        assertThat(last(ex, "job_test", "pause", StepStatus.FAILED).error().retryable()).isFalse();
    }

    @Test
    void theRecordedRulesRetriedAMalformedWaitDurationAsAnError() {
        SampleStepExecutor ex = SampleContent.executor();
        Outcome o = run(LEAF_ERROR, ex, WorkflowInterpreter.Rules.RECORDED_BEFORE_INTERPRETER_RULES);

        assertThat(o.errorClass()).isEqualTo("error");
        assertThat(ids(ex, "job_test", StepStatus.FAILED)).containsExactly("pause", "pause");
    }

    /** A first step with a compensation, a fork after it, and a catch flow that says where the job failed. */
    static final String CONTAINER = """
            apiVersion: iaf.platform/v1
            kind: Workflow
            metadata: { name: t-container, title: Container, noun: thing, verb: fork, owner: team-x, version: 1.0.0 }
            workflow:
              do:
                - get:
                    name: Get
                    call: provider
                    with: { provider: vsphere, action: vm.get, input: { id: vm-1042 } }
                    compensate:
                      - undo:
                          name: Undo
                          notify: { channel: "#x", message: "undo {{ $json.name }}" }
                - both:
                    name: Both
                    fork:
                      branches:
                        - - one:
                              name: One
                              notify: { channel: "#x", message: "one" }
                        - - two:
                              name: Two
                              notify: { channel: "#x", message: "two" }
              catch:
                do:
                  - tell:
                      name: Tell
                      notify: { channel: "#x", message: "failed at {{ $error.step }} ({{ $error.class }}): {{ $error.message }}" }
            """;

    /** An executor whose branch runner breaks: an exception no step's own handling sees. */
    static SampleStepExecutor brokenBranches() {
        return new SampleStepExecutor(SampleContent.providers(), SampleContent::child, SampleContent.types(), T0) {
            @Override
            public List<JsonNode> runBranches(JobContext ctx, Step step, List<Supplier<JsonNode>> branches) {
                throw new IllegalStateException("the branch runner broke");
            }
        };
    }

    @Test
    void anExceptionInAContainerFailsItThroughCompensationAndTheCatchFlow() {
        SampleStepExecutor ex = brokenBranches();
        Outcome o = run(CONTAINER, ex, WorkflowInterpreter.Rules.CURRENT);

        assertThat(o.status()).isEqualTo(JobStatus.ROLLED_BACK);
        assertThat(o.failedStep()).isEqualTo("both");
        assertThat(o.errorClass()).isEqualTo("error");
        assertThat(o.message()).isEqualTo("the branch runner broke");
        assertThat(last(ex, "job_test", "both", StepStatus.FAILED).error().errorClass()).isEqualTo("error");
        assertThat(last(ex, "job_test", "undo", StepStatus.SUCCEEDED).output().path("message").asText()).isEqualTo("undo web-1042");
        assertThat(ids(ex, "job_test", StepStatus.COMPENSATED)).containsExactly("get");
        assertThat(last(ex, "job_test", "tell", StepStatus.SUCCEEDED).output().path("message").asText())
                .isEqualTo("failed at both (error): the branch runner broke");
    }

    @Test
    void theRecordedRulesThrewAnExceptionInAContainerOutOfTheWalk() {
        WorkflowDefinition d = WorkflowYaml.parse(CONTAINER);
        WorkflowInterpreter walk = new WorkflowInterpreter(d, brokenBranches(), context(d, Map.of()), null,
                WorkflowInterpreter.Rules.RECORDED_BEFORE_INTERPRETER_RULES);

        assertThatThrownBy(walk::run).isInstanceOf(IllegalStateException.class).hasMessage("the branch runner broke");
    }

    /** Outputs that cannot be evaluated, on an executor whose log breaks when it is told so. */
    static final String OUTPUTS = """
            apiVersion: iaf.platform/v1
            kind: Workflow
            metadata: { name: t-outputs, title: Outputs, noun: thing, verb: report, owner: team-x, version: 1.0.0 }
            workflow:
              do:
                - get:
                    name: Get
                    call: provider
                    with: { provider: vsphere, action: vm.get, input: { id: vm-1042 } }
              catch:
                do:
                  - tell:
                      name: Tell
                      notify: { channel: "#x", message: "failed at {{ $error.step }} ({{ $error.class }})" }
              outputs:
                name: "{{ $nope.name }}"
            """;

    @Test
    void anExceptionWhileTheOutputsAreEvaluatedFailsTheJobAtOutputsThroughTheCatchFlow() {
        SampleStepExecutor ex = new SampleStepExecutor(SampleContent.providers(), SampleContent::child, SampleContent.types(), T0) {
            @Override
            public void log(JobContext ctx, String message) {
                if (message.startsWith("Output ")) {
                    throw new IllegalStateException("the job log is gone");
                }
                super.log(ctx, message);
            }
        };
        Outcome o = run(OUTPUTS, ex, WorkflowInterpreter.Rules.CURRENT);

        assertThat(o.status()).isEqualTo(JobStatus.FAILED);
        assertThat(o.failedStep()).isEqualTo(WorkflowInterpreter.OUTPUTS);
        assertThat(o.errorClass()).isEqualTo("error");
        assertThat(o.message()).isEqualTo("the job log is gone");
        assertThat(last(ex, "job_test", "tell", StepStatus.SUCCEEDED).output().path("message").asText()).isEqualTo("failed at outputs (error)");
    }

    /** A try whose catch names its failure {@code oops}, and a later step that still reads the name. */
    static final String CATCH_ALIAS = """
            apiVersion: iaf.platform/v1
            kind: Workflow
            metadata: { name: t-catch-alias, title: Catch alias, noun: thing, verb: catch, owner: team-x, version: 1.0.0 }
            workflow:
              do:
                - guarded:
                    name: Guarded
                    try:
                      do:
                        - broken:
                            name: Broken
                            call: provider
                            with: { provider: vsphere, action: vm.doesNotExist, input: { id: vm-1 } }
                      catch:
                        as: oops
                        do:
                          - note:
                              name: Note
                              notify: { channel: "#x", message: "caught {{ $oops.message }}" }
                - after:
                    name: After
                    notify: { channel: "#x", message: "after {{ $oops.message }}" }
                    pauseOnFailure: false
            """;

    @Test
    void aCatchAliasNamesTheFailureOnlyInsideItsCatch() {
        SampleStepExecutor ex = SampleContent.executor();
        Outcome o = run(CATCH_ALIAS, ex, WorkflowInterpreter.Rules.CURRENT);

        assertThat(last(ex, "job_test", "note", StepStatus.SUCCEEDED).output().path("message").asText())
                .isEqualTo("caught Provider vsphere has no action 'vm.doesNotExist'");
        assertThat(o.status()).isEqualTo(JobStatus.FAILED);
        assertThat(o.failedStep()).isEqualTo("after");
        assertThat(o.errorClass()).isEqualTo("expression");
        assertThat(o.message()).contains("$oops");
    }

    @Test
    void theRecordedRulesLeftACatchAliasBoundAfterItsCatch() {
        SampleStepExecutor ex = SampleContent.executor();
        Outcome o = run(CATCH_ALIAS, ex, WorkflowInterpreter.Rules.RECORDED_BEFORE_INTERPRETER_RULES);

        assertThat(o.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(last(ex, "job_test", "after", StepStatus.SUCCEEDED).output().path("message").asText())
                .isEqualTo("after Provider vsphere has no action 'vm.doesNotExist'");
    }

    /** A try whose catch keeps the default name: {@code $error} there is the failure it caught. */
    static final String CATCH_ERROR = """
            apiVersion: iaf.platform/v1
            kind: Workflow
            metadata: { name: t-catch-error, title: Catch error, noun: thing, verb: catch, owner: team-x, version: 1.0.0 }
            workflow:
              do:
                - guarded:
                    name: Guarded
                    try:
                      do:
                        - broken:
                            name: Broken
                            call: provider
                            with: { provider: vsphere, action: vm.doesNotExist, input: { id: vm-1 } }
                      catch:
                        do:
                          - note:
                              name: Note
                              notify: { channel: "#x", message: "caught at {{ $error.step }}: {{ $error.message }}" }
                - after:
                    name: After
                    notify: { channel: "#x", message: "after [{{ $error.message }}]" }
            """;

    @Test
    void errorInsideAStepCatchIsTheFailureItCaughtAndNothingAfterIt() {
        SampleStepExecutor ex = SampleContent.executor();
        Outcome o = run(CATCH_ERROR, ex, WorkflowInterpreter.Rules.CURRENT);

        assertThat(o.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(last(ex, "job_test", "note", StepStatus.SUCCEEDED).output().path("message").asText())
                .isEqualTo("caught at broken: Provider vsphere has no action 'vm.doesNotExist'");
        assertThat(last(ex, "job_test", "after", StepStatus.SUCCEEDED).output().path("message").asText()).doesNotContain("vm.doesNotExist");
    }

    @Test
    void theRecordedRulesReadTheJobsErrorInsideAStepCatch() {
        SampleStepExecutor ex = SampleContent.executor();
        run(CATCH_ERROR, ex, WorkflowInterpreter.Rules.RECORDED_BEFORE_INTERPRETER_RULES);

        assertThat(last(ex, "job_test", "note", StepStatus.SUCCEEDED).output().path("message").asText()).doesNotContain("vm.doesNotExist");
    }
}
