package com.iaf.platform.runs.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.domain.Effect;
import com.iaf.platform.common.ports.PolicyPort.PolicyDecision;
import com.iaf.platform.common.ports.PolicyPort.Rule;
import com.iaf.platform.runs.SampleContent;
import com.iaf.platform.runs.domain.Approval;
import com.iaf.platform.runs.domain.Attention;
import com.iaf.platform.runs.domain.Batch;
import com.iaf.platform.runs.domain.Batch.BatchRow;
import com.iaf.platform.runs.domain.Batch.Wave;
import com.iaf.platform.runs.domain.ChangeTicket;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.runs.domain.JobEvent;
import com.iaf.platform.runs.domain.JobStatus;
import com.iaf.platform.runs.domain.JobStep;
import com.iaf.platform.runs.domain.JobStep.StepError;
import com.iaf.platform.runs.domain.JobStep.StepStatus;
import com.iaf.platform.runs.domain.Preview;
import com.iaf.platform.runs.ports.outbound.ProjectionStore;
import com.iaf.platform.runs.ports.outbound.ProjectionStore.JobQuery;
import com.iaf.platform.workflows.domain.StepType;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowDefinition.RolloutPolicy;

/** What every {@link ProjectionStore} does, in memory and in a database. */
abstract class ProjectionStoreContract {

    static final Instant T0 = Instant.parse("2026-10-17T10:00:00.123456789Z");
    static final ObjectMapper JSON = new ObjectMapper();
    static final WorkflowDefinition RESTART = SampleContent.workflow("vm-restart");
    static final WorkflowDefinition PATCH = SampleContent.workflow("patch-cve");

    /** An empty store. */
    abstract ProjectionStore newStore();

    /** A new store over the same storage as the last one made; null when the storage does not outlive the store. */
    abstract ProjectionStore reopen();

    static Job job(String id, String workflow, JobStatus status, Instant startedAt) {
        return Job.builder().id(id).workflow(workflow).title(workflow).version("1.0.0").status(status).requester(SampleContent.ANA)
                .channel("rest").startedAt(startedAt).kind(Job.Kind.SINGLE).steps(List.of()).approvals(List.of())
                .definition("patch-cve".equals(workflow) ? PATCH : RESTART).build();
    }

    static Approval approval(String id, String jobId, Instant requestedAt, Approval.Status status) {
        return Approval.builder().id(id).jobId(jobId).workflow("vm-restart").step("approval").group("compute-approvers")
                .message("Restart web-01?").requestedAt(requestedAt).timeout("4h").status(status).build();
    }

    /** A job with every part filled in, as the engine and the folding produce them. */
    static Job rich(String id) {
        ObjectNode input = JSON.createObjectNode().put("vm", "web-01").put("cpu", 4).put("ratio", 0.5).put("force", true).put("note", "ü \"✓\"");
        input.putArray("tags").add("prod").add(2);
        input.putNull("unset");
        JobStep stopped = JobStep.builder().id("stop").name("Stop the VM").type(StepType.PROVIDER).status(StepStatus.SUCCEEDED).attempt(1)
                .startedAt(T0.plusNanos(1)).endedAt(T0.plusMillis(41)).durationMs(40L).input(input)
                .output(JSON.createObjectNode().put("state", "stopped")).build();
        JobStep validate = JobStep.builder().id("validate").name("Validate").type(StepType.SCRIPT).status(StepStatus.FAILED).attempt(2)
                .startedAt(T0.plusSeconds(1)).error(new StepError("timeout", "no answer in 30s", true)).items(3).build();
        Preview preview = new Preview("vm-restart", "1.0.0", List.of(new Preview.PreviewStep("stop", "Stop the VM", Effect.CHANGE, "stops web-01")),
                List.of(new Preview.AffectedResource("vm-1", "vm", "web-01", "input.vm")), new Preview.Cost(12.5, "EUR", "per month"),
                new PolicyDecision("needs-approval", List.of(new Rule("prod-restart", "needs-approval", "production needs a sign-off")), "opa"));
        Approval decided = approval("apr_" + id, id, T0.plusSeconds(2), Approval.Status.APPROVED).withDecidedBy("gonzalo")
                .withDecidedAt(T0.plusSeconds(30)).withComment("go").withPreview(preview);
        Batch batch = new Batch(List.of(new BatchRow(0, input, "job_child", "succeeded", null, 0), new BatchRow(1, input, null, "failed", "timeout at stop", 1)),
                List.of(new Wave(0, 50, 1, "succeeded", T0, T0.plusSeconds(1)), new Wave(1, 100, 1, "running", T0.plusSeconds(2), null)),
                new RolloutPolicy(List.of(50, 100), 2, 10), "stopped by gonzalo");
        return Job.builder().id(id).workflow("vm-restart").title("Restart a VM").version(RESTART.version()).status(JobStatus.NEEDS_ATTENTION)
                .requester(SampleContent.GONZALO).channel("mcp").startedAt(T0).endedAt(T0.plusSeconds(90)).durationMs(90_000L).parentId("job_parent")
                .kind(Job.Kind.BATCH).progress(new Job.Progress(1, 2, 1)).input(input).output(JSON.createObjectNode().put("ok", false))
                .steps(List.of(stopped, validate)).approvals(List.of(decided)).preview(preview)
                .change(new ChangeTicket("CHG0042", "https://changes.example/CHG0042", "open")).batch(batch)
                .attention(new Attention("validate", new StepError("timeout", "no answer in 30s", true), Attention.OPTIONS, T0.plusSeconds(3), "P7D"))
                .traceId("trace_abc").definition(RESTART).environment("prod").errorClass("timeout").failedStep("validate")
                .policy(preview.policy()).resume(new Job.Resume("validate", List.of(stopped))).row(4).preApproved(true).build();
    }

    static JobEvent event(String jobId, long seq, Instant at, String type) {
        return new JobEvent(seq, at, jobId, type, JSON.createObjectNode().put("event", "job." + type).put("n", seq));
    }

    @Test
    void readsAJobBackExactly() {
        ProjectionStore store = newStore();
        Job job = rich("job_rich");
        store.save(job);
        assertThat(store.job("job_rich")).contains(job);
        assertThat(store.job("job_rich").orElseThrow().definition()).isEqualTo(RESTART);
        assertThat(store.approval("apr_job_rich")).contains(job.approvals().get(0));
    }

    @Test
    void anUnknownIdIsEmpty() {
        ProjectionStore store = newStore();
        assertThat(store.job("job_unknown")).isEmpty();
        assertThat(store.job(null)).isEmpty();
        assertThat(store.approval("apr_unknown")).isEmpty();
        assertThat(store.approval(null)).isEmpty();
        assertThat(store.jobs(JobQuery.ALL)).isEmpty();
        assertThat(store.children("job_unknown")).isEmpty();
        assertThat(store.approvals()).isEmpty();
        assertThat(store.history("job_unknown")).isEmpty();
        assertThat(store.lastSeq("job_unknown")).isZero();
    }

    @Test
    void replacesAJobById() {
        ProjectionStore store = newStore();
        Job queued = job("job_1", "vm-restart", JobStatus.QUEUED, T0);
        store.save(queued);
        Job done = queued.withStatus(JobStatus.SUCCEEDED).withEndedAt(T0.plusSeconds(5)).withDurationMs(5000L);
        store.save(done);
        assertThat(store.jobs(JobQuery.ALL)).containsExactly(done);
        assertThat(store.job("job_1")).contains(done);
    }

    @Test
    void listsJobsNewestFirstThenById() {
        ProjectionStore store = newStore();
        store.save(job("job_b", "vm-restart", JobStatus.RUNNING, T0));
        store.save(job("job_a", "vm-restart", JobStatus.RUNNING, T0));
        store.save(job("job_old", "vm-restart", JobStatus.RUNNING, T0.minusNanos(1)));
        store.save(job("job_new", "vm-restart", JobStatus.RUNNING, T0.plusNanos(1)));
        store.save(job("job_unstarted", "vm-restart", JobStatus.QUEUED, null));
        assertThat(store.jobs(JobQuery.ALL)).extracting(Job::id).containsExactly("job_new", "job_a", "job_b", "job_old", "job_unstarted");
        assertThat(store.jobs(null)).extracting(Job::id).containsExactly("job_new", "job_a", "job_b", "job_old", "job_unstarted");
    }

    @Test
    void filtersAndLimitsAListing() {
        ProjectionStore store = newStore();
        store.save(job("job_1", "vm-restart", JobStatus.SUCCEEDED, T0));
        store.save(job("job_2", "vm-restart", JobStatus.FAILED, T0.plusSeconds(1)));
        store.save(job("job_3", "patch-cve", JobStatus.SUCCEEDED, T0.plusSeconds(2)));
        store.save(job("job_4", "patch-cve", JobStatus.SUCCEEDED, T0.plusSeconds(3)).withParentId("job_3").withRow(0));
        store.save(job("job_5", "vm-restart", JobStatus.SUCCEEDED, T0.plusSeconds(4)));

        assertThat(store.jobs(new JobQuery("vm-restart", null, null, false, 0))).extracting(Job::id).containsExactly("job_5", "job_2", "job_1");
        assertThat(store.jobs(new JobQuery(null, JobStatus.SUCCEEDED, null, false, 0))).extracting(Job::id).containsExactly("job_5", "job_4", "job_3", "job_1");
        assertThat(store.jobs(new JobQuery(null, null, null, true, 0))).extracting(Job::id).containsExactly("job_5", "job_3", "job_2", "job_1");
        assertThat(store.jobs(new JobQuery(null, null, "job_3", false, 0))).extracting(Job::id).containsExactly("job_4");
        assertThat(store.jobs(new JobQuery("patch-cve", JobStatus.SUCCEEDED, null, true, 0))).extracting(Job::id).containsExactly("job_3");
        assertThat(store.jobs(new JobQuery(null, null, null, true, 2))).extracting(Job::id).containsExactly("job_5", "job_3");
        assertThat(store.jobs(new JobQuery("nope", null, null, false, 0))).isEmpty();
    }

    @Test
    void listsChildrenRowsFirstThenOldestFirst() {
        ProjectionStore store = newStore();
        store.save(job("job_parent", "patch-cve", JobStatus.RUNNING, T0));
        store.save(job("job_r1", "patch-cve", JobStatus.RUNNING, T0.plusSeconds(9)).withParentId("job_parent").withRow(1));
        store.save(job("job_r0", "patch-cve", JobStatus.RUNNING, T0.plusSeconds(10)).withParentId("job_parent").withRow(0));
        store.save(job("job_sub_late", "vm-restart", JobStatus.RUNNING, T0.plusSeconds(5)).withParentId("job_parent"));
        store.save(job("job_sub_b", "vm-restart", JobStatus.RUNNING, T0.plusSeconds(1)).withParentId("job_parent"));
        store.save(job("job_sub_a", "vm-restart", JobStatus.RUNNING, T0.plusSeconds(1)).withParentId("job_parent"));
        store.save(job("job_elsewhere", "vm-restart", JobStatus.RUNNING, T0).withParentId("job_other"));
        assertThat(store.children("job_parent")).extracting(Job::id).containsExactly("job_r0", "job_r1", "job_sub_a", "job_sub_b", "job_sub_late");
    }

    @Test
    void keepsApprovalsByIdNewestFirstEvenOnceTheirJobDropsThem() {
        ProjectionStore store = newStore();
        Approval first = approval("apr_1", "job_1", T0, Approval.Status.PENDING);
        Approval second = approval("apr_2", "job_1", T0.plusNanos(1), Approval.Status.PENDING);
        Approval other = approval("apr_0", "job_2", T0.plusNanos(1), Approval.Status.PENDING);
        store.save(job("job_1", "vm-restart", JobStatus.WAITING_APPROVAL, T0).withApprovals(List.of(first, second)));
        store.save(job("job_2", "vm-restart", JobStatus.WAITING_APPROVAL, T0).withApprovals(List.of(other)));
        Approval decided = second.withStatus(Approval.Status.REJECTED).withDecidedBy("gonzalo").withDecidedAt(T0.plusSeconds(60));
        store.save(job("job_1", "vm-restart", JobStatus.FAILED, T0).withApprovals(List.of(decided)));

        assertThat(store.approvals()).containsExactly(other, decided, first);
        assertThat(store.approval("apr_1")).contains(first);
        assertThat(store.approval("apr_2")).contains(decided);
    }

    @Test
    void keepsEachJobsHistoryInOrder() {
        ProjectionStore store = newStore();
        store.append(event("job_1", 1, T0, "job"));
        store.append(event("job_2", 1, T0, "job"));
        store.append(event("job_1", 2, T0.plusNanos(1), "step"));
        store.append(event("job_1", 3, null, "log"));
        List<JobEvent> history = store.history("job_1");
        assertThat(history).extracting(JobEvent::seq).containsExactly(1L, 2L, 3L);
        assertThat(history.get(1).at()).isEqualTo(T0.plusNanos(1));
        assertThat(history.get(1).type()).isEqualTo("step");
        assertThat(history.get(1).data().toString()).isEqualTo("{\"event\":\"job.step\",\"n\":2}");
        assertThat(history.get(2).at()).isNull();
        assertThat(store.lastSeq("job_1")).isEqualTo(3);
        assertThat(store.lastSeq("job_2")).isEqualTo(1);
        assertThat(store.history("job_2")).hasSize(1);
    }

    @Test
    void keepsTheIndexAcrossARestart() {
        ProjectionStore store = newStore();
        Job job = rich("job_rich");
        store.save(job);
        store.save(job("job_plain", "patch-cve", JobStatus.SUCCEEDED, T0.plusSeconds(1)));
        store.append(event("job_rich", 1, T0, "job"));
        store.append(event("job_rich", 2, T0.plusSeconds(1), "step"));

        ProjectionStore after = reopen();
        assumeTrue(after != null, "this store does not outlive the process");
        assertThat(after.job("job_rich")).contains(job);
        assertThat(after.jobs(JobQuery.ALL)).extracting(Job::id).containsExactly("job_plain", "job_rich");
        assertThat(after.approval("apr_job_rich")).contains(job.approvals().get(0));
        assertThat(after.history("job_rich")).extracting(JobEvent::seq).containsExactly(1L, 2L);
        assertThat(after.lastSeq("job_rich")).isEqualTo(2);
    }
}
