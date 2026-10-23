package com.iaf.console.fake.runs.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.fake.PlatformFake;
import com.iaf.console.fake.common.ApiException;
import com.iaf.console.fake.common.Json;
import com.iaf.console.fake.identity.domain.Caller;
import com.iaf.console.fake.identity.domain.Delegation;
import com.iaf.console.fake.identity.domain.DevUsers;
import com.iaf.console.fake.identity.domain.RequestContext;
import com.iaf.console.fake.identity.domain.Scopes;
import com.iaf.console.fake.quotas.application.Quotas;
import com.iaf.console.fake.runs.domain.ApprovalRecord;
import com.iaf.console.fake.runs.domain.Job;
import com.iaf.console.fake.runs.domain.JobEventRecord;
import com.iaf.console.fake.runs.domain.JobStatus;
import com.iaf.console.fake.runs.domain.StepRecord;
import com.iaf.console.kit.errors.InvalidInputException;

/** The interpreter through the jobs service: a fake without the demo, every step instant. */
class JobsTest {

    private static final String ACCOUNT = "123456789012";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private static PlatformFake fake;
    private static Jobs jobs;
    private static String image;
    private static Caller console;

    @BeforeAll
    static void start() {
        fake = PlatformFake.builder().demo(false).stepDelay(Duration.ZERO).generateDemoKey(false).build();
        jobs = fake.jobs();
        Job build = start(DevUsers.byName("mia"), "build-vm-image", Json.object().put("os", "ubuntu-24.04"));
        await(build, Job::finished);
        assertThat(build.status()).isEqualTo(JobStatus.SUCCEEDED);
        image = build.output().path("imageId").asText();
        Caller service = new Caller("sa-console", "console", null, List.of("team-compute"), List.of(), false, Caller.SERVICE,
                java.util.Set.copyOf(Scopes.PRESETS.get("console")), ACCOUNT, null);
        console = service.withDelegation(new Delegation("dana", "Dana Reyes", "dana@example.com", List.of("team-compute", "developer"), ACCOUNT, "global",
                "jti-1"));
    }

    @AfterAll
    static void stop() {
        fake.close();
    }

    private static Job start(Caller caller, String workflow, ObjectNode input) {
        return start(caller, RequestContext.NONE, workflow, input);
    }

    private static Job start(Caller caller, RequestContext context, String workflow, ObjectNode input) {
        return jobs.start(caller, context, new Jobs.StartRequest(workflow, null, input, "rest", null, null), "rest");
    }

    private static void await(Job job, Predicate<Job> until) {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (!until.test(job)) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("job " + job.id() + " stayed " + job.status().id() + ": " + job.detail(false, List.of()));
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static ObjectNode vmInput(String name, String size, String environment) {
        return Json.object().put("name", name).put("image", image).put("size", size).put("environment", environment);
    }

    private static List<String> events(Job job) {
        return job.events().stream().map(JobEventRecord::event).toList();
    }

    @Test
    void aVmJobWalksEverySwitchBranchAndRegistersTheResource() {
        Job job = start(console, new RequestContext(null, "req-1", Map.of("tab", "t1"), null, null, null), "create-vm", vmInput("web-01", "large", "prod"));
        await(job, Job::finished);
        assertThat(job.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(job.output().path("vmId").asText()).isNotBlank();
        assertThat(job.steps()).extracting(StepRecord::id).containsExactly("useImage", "sizeToSpec", "largeSpec", "createVm", "waitReady", "registerVm");
        assertThat(job.step("largeSpec").status()).isEqualTo("succeeded");
        assertThat(events(job)).startsWith("job.started", "job.step").endsWith("job.finished");
        assertThat(job.context().accountId()).isEqualTo(ACCOUNT);
        assertThat(job.context().labels()).containsEntry("tab", "t1");
        var vm = fake.registry().find(job.output().path("vmId").asText()).orElseThrow();
        assertThat(vm.attributes().path("cpu").asInt()).isEqualTo(8);
        assertThat(vm.accountId()).isEqualTo(ACCOUNT);
        assertThat(vm.wfrn()).contains(ACCOUNT);
        assertThat(vm.builtFrom()).containsExactly(image);
        assertThat(fake.audit().chain(job.id()).orElseThrow().sealed()).isTrue();
    }

    @Test
    void badInputUnknownWorkflowAndPolicyAreRefused() {
        assertThatThrownBy(() -> start(console, "create-vm", Json.object().put("name", "no-image"))).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> start(console, "nope", Json.object())).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(404));
        assertThatThrownBy(() -> jobs.start(console, RequestContext.NONE, new Jobs.StartRequest("create-vm", "^9", vmInput("x", "small", "dev"), null, null, null),
                "rest")).isInstanceOf(ApiException.class).hasMessageContaining("workflow.versionNotFound");
        assertThatThrownBy(() -> start(DevUsers.byName("raj"), "vm-restart", Json.object().put("vmId", "vm-1").put("reason", "a dba of another team")))
                .isInstanceOf(ApiException.class).satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(403));
        assertThat(Jobs.allowed(console, fake.content().workflow("vm-restart").orElseThrow())).as("the owning team runs its own workflows").isTrue();
    }

    @Test
    void anApprovalPausesTheJobUntilAnApproverDecides() {
        Job vmJob = start(console, "create-vm", vmInput("web-02", "small", "prod"));
        await(vmJob, Job::finished);
        String vmId = vmJob.output().path("vmId").asText();
        Job update = start(console, "update-vm", Json.object().put("vmId", vmId).put("cpu", 8).put("reason", "load"));
        await(update, j -> j.status() == JobStatus.WAITING_APPROVAL);
        ApprovalRecord approval = jobs.approvals("pending", DevUsers.byName("gonzalo")).stream().filter(a -> a.jobId().equals(update.id())).findFirst()
                .orElseThrow();
        assertThat(approval.group()).isEqualTo("compute-approvers");
        assertThatThrownBy(() -> jobs.decideApproval(approval, true, DevUsers.byName("dana"), null)).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(403));
        jobs.decideApproval(approval, true, DevUsers.byName("mia"), "fine");
        await(update, Job::finished);
        assertThat(update.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(approval.status()).isEqualTo("approved");
        assertThat(fake.registry().find(vmId).orElseThrow().attributes().path("cpu").asInt()).isEqualTo(8);
        assertThat(events(update)).contains("job.approval-requested", "job.approval-decided");
        assertThatThrownBy(() -> jobs.decideApproval(approval, false, DevUsers.byName("mia"), null)).hasMessageContaining("approval.notPending");
    }

    @Test
    void aRejectedApprovalFailsTheJob() {
        Job vmJob = start(console, "create-vm", vmInput("web-03", "small", "prod"));
        await(vmJob, Job::finished);
        Job update = start(console, "update-vm", Json.object().put("vmId", vmJob.output().path("vmId").asText()).put("cpu", 8));
        await(update, j -> j.status() == JobStatus.WAITING_APPROVAL);
        ApprovalRecord approval = update.approvals().get(0);
        jobs.decideApproval(approval, false, DevUsers.byName("mia"), "no");
        await(update, Job::finished);
        assertThat(update.status()).isEqualTo(JobStatus.FAILED);
        assertThat(update.errorClass()).isEqualTo("approval.rejected");
        assertThat(update.failedStep()).isEqualTo("approval");
    }

    @Test
    void aFailingStepNeedsAttentionAndIsRetriedOrSkippedOrAborted() {
        Job vmJob = start(console, "create-vm", vmInput("web-04", "small", "dev"));
        await(vmJob, Job::finished);
        String vmId = vmJob.output().path("vmId").asText();
        Caller ops = DevUsers.byName("gonzalo");
        for (String action : new String[] {"retry", "skip", "abort"}) {
            fake.world().mutate(Json.read("{\"provider\":\"vsphere\",\"action\":\"vm.waitForPowerState\",\"match\":{\"id\":\"" + vmId
                    + "\"},\"fail\":{\"class\":\"timeout\",\"message\":\"slow\",\"retryable\":true,\"times\":1}}"));
            Job restart = start(ops, "vm-restart", Json.object().put("vmId", vmId).put("reason", "stuck after a kernel update"));
            await(restart, j -> j.status() == JobStatus.WAITING_APPROVAL);
            jobs.decideApproval(restart.approvals().get(0), true, DevUsers.byName("mia"), null);
            await(restart, j -> j.status() == JobStatus.NEEDS_ATTENTION);
            assertThat(restart.attention().step()).isEqualTo("validate");
            assertThat(restart.attention().error().errorClass()).isEqualTo("timeout");
            switch (action) {
                case "retry" -> jobs.retry(restart, Json.object(), ops, RequestContext.NONE);
                case "skip" -> jobs.skip(restart, Json.object().put("step", "validate"), ops);
                default -> jobs.abort(restart, ops);
            }
            await(restart, Job::finished);
            if (action.equals("abort")) {
                assertThat(restart.status()).isEqualTo(JobStatus.FAILED);
                assertThat(restart.step("notifyOwner").status()).isEqualTo("succeeded");
            } else {
                assertThat(restart.status()).isEqualTo(JobStatus.SUCCEEDED);
                assertThat(restart.step("validate").status()).isEqualTo(action.equals("skip") ? "skipped" : "succeeded");
            }
            assertThat(events(restart)).contains("job.attention", "job.attention-resolved");
        }
    }

    @Test
    void aDecisionTheMomentAPauseIsSeenIsNeverRefused() {
        Job vmJob = start(console, "create-vm", vmInput("web-06", "small", "dev"));
        await(vmJob, Job::finished);
        String vmId = vmJob.output().path("vmId").asText();
        Caller ops = DevUsers.byName("gonzalo");
        String bucket = Quotas.jobKey(ops);
        fake.quotas().put(bucket, Json.object().put("limitPerMinute", 600_000).put("burst", 1_000));
        try {
            race(ops, vmId);
        } finally {
            fake.quotas().put(bucket, Json.object().put("limitPerMinute", Quotas.DEFAULT_LIMIT).put("burst", Quotas.DEFAULT_BURST));
        }
    }

    private static void race(Caller ops, String vmId) {
        for (int i = 0; i < 200; i++) {
            fake.world().mutate(Json.read("{\"provider\":\"vsphere\",\"action\":\"vm.waitForPowerState\",\"match\":{\"id\":\"" + vmId
                    + "\"},\"fail\":{\"class\":\"timeout\",\"message\":\"slow\",\"retryable\":true,\"times\":1}}"));
            Job restart = start(ops, "vm-restart", Json.object().put("vmId", vmId).put("reason", "race " + i));
            // decide as soon as the approval is listed, the way the demo seed and a quick approver do
            ApprovalRecord approval = spinUntil(restart, () -> jobs.approvals("pending", ops).stream().filter(a -> a.jobId().equals(restart.id()))
                    .findFirst().orElse(null));
            jobs.decideApproval(approval, true, DevUsers.byName("mia"), null);
            spinUntil(restart, () -> restart.status() == JobStatus.NEEDS_ATTENTION ? restart : null);
            jobs.retry(restart, Json.object(), ops, RequestContext.NONE);
            await(restart, Job::finished);
            assertThat(restart.status()).as("run %d", i).isEqualTo(JobStatus.SUCCEEDED);
        }
    }

    /** Polls without sleeping, so a decision lands as close as it can to the moment the pause becomes visible. */
    private static <T> T spinUntil(Job job, Supplier<T> seen) {
        Instant deadline = Instant.now().plus(TIMEOUT);
        T value;
        while ((value = seen.get()) == null) {
            if (Instant.now().isAfter(deadline) || job.finished()) {
                throw new AssertionError("job " + job.id() + " stayed " + job.status().id());
            }
            Thread.onSpinWait();
        }
        return value;
    }

    @Test
    void aJobWaitingOnAResourceHoldsItUntilItEnds() {
        Job vmJob = start(console, "create-vm", vmInput("web-07", "small", "prod"));
        await(vmJob, Job::finished);
        var vm = fake.registry().find(vmJob.output().path("vmId").asText()).orElseThrow();
        assertThat(jobs.holds(vm)).isFalse();
        Job update = start(console, "update-vm", Json.object().put("vmId", vm.id()).put("cpu", 4).put("reason", "load"));
        await(update, j -> j.status() == JobStatus.WAITING_APPROVAL);
        assertThat(jobs.holds(vm)).isTrue();
        jobs.cancel(update, DevUsers.byName("gonzalo"));
        await(update, Job::finished);
        assertThat(jobs.holds(vm)).isFalse();
    }

    @Test
    void aFailedCreateIsCompensatedAndTheCatchRuns() {
        fake.world().mutate(Json.read("{\"provider\":\"dns-records\",\"action\":\"record.create\",\"match\":{\"id\":\"*\"},"
                + "\"fail\":{\"class\":\"zone_not_found\",\"message\":\"no zone\",\"retryable\":false,\"times\":10}}"));
        Job job = start(DevUsers.byName("dana"), "reserve-ip-address", Json.object().put("pool", "lab").put("hostname", "web-77"));
        await(job, j -> j.status() == JobStatus.NEEDS_ATTENTION);
        jobs.abort(job, DevUsers.byName("dana"));
        await(job, Job::finished);
        fake.world().mutate(Json.read("{\"provider\":\"dns-records\",\"fail\":null}"));
        assertThat(job.status()).isEqualTo(JobStatus.ROLLED_BACK);
        assertThat(job.step("reserve").status()).isEqualTo("compensated");
        assertThat(job.step("release").status()).isEqualTo("succeeded");
        assertThat(job.failedStep()).isEqualTo("publishRecord");
    }

    @Test
    void cancelStopsTheJobWithoutCompensation() {
        fake.world().mutate(Json.read("{\"provider\":\"vsphere\",\"action\":\"vm.waitForPowerState\",\"match\":{\"id\":\"*\"},"
                + "\"fail\":{\"class\":\"timeout\",\"message\":\"slow\",\"retryable\":true,\"times\":1}}"));
        Job job = start(console, "create-vm", vmInput("web-05", "small", "dev"));
        await(job, j -> j.status() == JobStatus.NEEDS_ATTENTION);
        jobs.cancel(job, DevUsers.byName("gonzalo"));
        await(job, Job::finished);
        assertThat(job.status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(events(job)).endsWith("job.cancelled");
        assertThat(job.step("createVm").status()).isEqualTo("succeeded");
        assertThatThrownBy(() -> jobs.cancel(job, DevUsers.byName("gonzalo"))).hasMessageContaining("job.finished");
    }

    @Test
    void aRerunReplaysTheStepsBeforeTheOneItStartsFrom() {
        Job job = start(console, "create-vm", vmInput("web-06", "medium", "dev"));
        await(job, Job::finished);
        Job rerun = jobs.rerun(job, Json.object().put("fromStep", "createVm"), console, RequestContext.NONE);
        await(rerun, Job::finished);
        assertThat(rerun.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(rerun.rerunOf()).isEqualTo(job.id());
        assertThat(rerun.step("useImage").replayed()).isTrue();
        assertThat(rerun.step("createVm").replayed()).isFalse();
        assertThat(rerun.output().path("vmId").asText()).isNotEqualTo(job.output().path("vmId").asText());
        assertThatThrownBy(() -> jobs.rerun(job, Json.object().put("fromStep", "nope"), console, RequestContext.NONE)).hasMessageContaining("step.unknown");
    }

    @Test
    void aBatchRunsRowsInWavesAndStopsWhenTooManyFail() {
        ObjectNode body = Json.object().put("workflow", "create-vm");
        var items = body.putArray("items");
        for (int i = 0; i < 4; i++) {
            items.addObject().put("name", "row-" + i);
        }
        items.addObject().put("name", "BAD NAME");
        body.set("defaults", Json.object().put("image", image).put("size", "small"));
        assertThatThrownBy(() -> jobs.startBatch(console, RequestContext.NONE, body, "batch")).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).body().path("rows").get(0).path("index").asInt()).isEqualTo(4));
        items.remove(4);
        body.set("rollout", Json.object().put("maxParallel", 2).set("waves", Json.array().add(50).add(100)));
        Job parent = jobs.startBatch(console, RequestContext.NONE, body, "batch");
        await(parent, Job::finished);
        assertThat(parent.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(parent.batch().waves()).hasSize(2);
        assertThat(parent.batch().rows()).allMatch(r -> "succeeded".equals(r.status()));
        assertThat(jobs.children(parent.id())).hasSize(4).allMatch(c -> c.row() != null && c.parentId().equals(parent.id()));
        assertThat(events(parent)).contains("batch.wave").endsWith("job.finished");
        assertThatThrownBy(() -> jobs.retryFailedRows(parent)).hasMessageContaining("batch.noFailedRows");
    }

    @Test
    void consoleAccountsListTheirAccountAndOthersSeeMaskedDetails() {
        Job job = start(console, "create-vm", vmInput("web-07", "small", "dev"));
        await(job, Job::finished);
        Caller otherConsole = new Caller("sa-other", "console", null, List.of("team-data"), List.of(), false, Caller.SERVICE,
                java.util.Set.copyOf(Scopes.PRESETS.get("console")), "210987654321", null);
        assertThat(jobs.visible(job.id(), otherConsole)).isEmpty();
        assertThat(jobs.list(JobFilter.none(), otherConsole).items()).noneMatch(j -> j.id().equals(job.id()));
        Caller teamMate = DevUsers.byName("dana");
        assertThat(jobs.visible(job.id(), teamMate)).isPresent();
        ObjectNode masked = jobs.detail(job, teamMate);
        assertThat(masked.path("input").path("name").asText()).isEqualTo("web-07");
        Caller platformPerson = DevUsers.byName("mia");
        assertThat(jobs.visible(job.id(), platformPerson)).isEmpty();
        ObjectNode own = jobs.detail(job, console);
        assertThat(own.path("output").path("vmId").asText()).isNotEqualTo("***");
        Jobs.Listing paged = jobs.list(new JobFilter(null, null, null, 2, ACCOUNT, null, null, null, Map.of(), 0L, null), DevUsers.byName("gonzalo"));
        assertThat(paged.items()).hasSize(2);
        assertThat(paged.next()).isNotNull();
    }
}
