package com.iaf.platform.runs.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.iaf.platform.common.demo.DemoReleases;
import com.iaf.platform.common.demo.DemoReleases.Story;
import com.iaf.platform.common.ports.TelemetryPort.CallSample;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.resources.domain.Resource;
import com.iaf.platform.runs.RunsTestWiring;
import com.iaf.platform.runs.domain.Approval;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.runs.domain.JobStatus;
import com.iaf.platform.runs.domain.JobStep.StepStatus;
import com.iaf.platform.runs.domain.Batch.Wave;
import com.iaf.platform.runs.ports.outbound.WorkflowExecutionPort;

/** The demo history: a fortnight of believable jobs, telemetry for every call, and three live states the engine picks up. */
class DemoRunsSeederTest {

    @Test
    void seedsHistoryTelemetryAndLiveStates() {
        RunsTestWiring w = new RunsTestWiring();
        WorkflowExecutionPort engine = RunsTestWiring.idleEngine();
        PlatformProperties props = new PlatformProperties();
        props.getContent().setDir("..");
        DemoRunsSeeder seeder = new DemoRunsSeeder(props, w.workflows, w.providers, w.resources, w.projection, w.events, w.telemetry, engine, w.clock);

        DemoRunsSeeder.Report report = seeder.seed();

        int canaryJobs = DemoReleases.STORIES.stream().filter(Story::canary).mapToInt(Story::jobs).sum();
        assertThat(report.jobs()).isEqualTo(DemoRunsSeeder.HISTORY_JOBS + canaryJobs);
        List<Job> all = w.projection.all();
        List<Job> history = all.stream().filter(j -> j.parentId() == null && j.status() != null && j.status().terminal()).toList();
        assertThat(history).hasSizeGreaterThanOrEqualTo(55);
        assertThat(history).allMatch(j -> j.definition() != null && j.endedAt() != null && j.durationMs() != null && j.durationMs() > 0);
        assertThat(history).allMatch(j -> !j.steps().isEmpty());
        Instant now = w.clock.instant();
        assertThat(history).allMatch(j -> j.startedAt().isAfter(now.minus(Duration.ofDays(15))) && j.startedAt().isBefore(now));
        assertThat(history.stream().map(Job::workflow).distinct().count()).isGreaterThanOrEqualTo(6);

        List<Job> failed = history.stream().filter(j -> j.status() == JobStatus.FAILED || j.status() == JobStatus.ROLLED_BACK).toList();
        assertThat(failed).hasSizeBetween(2, 10);
        assertThat(failed).allMatch(j -> j.errorClass() != null && j.failedStep() != null);
        assertThat(failed.stream().map(Job::errorClass).collect(java.util.stream.Collectors.toSet()))
                .isSubsetOf(Set.of("rate_limited", "not_found", "timeout", "unreachable", "apply_failed", "verify_failed", "assertion_failed", "expression", "error",
                        "replica_set_not_ready"));
        assertThat(history.stream().filter(j -> j.status() == JobStatus.CANCELLED).count()).isBetween(1L, 8L);
        assertThat(history).anyMatch(j -> j.change() != null && j.change().id().startsWith("CHG00") && "closed".equals(j.change().state()));
        assertThat(history).anyMatch(j -> j.approvals().stream().anyMatch(a -> a.status() == Approval.Status.APPROVED && a.decidedBy() != null));
        assertThat(history).anyMatch(j -> j.preview() != null);

        // telemetry: a sample for every provider call of every seeded job
        ArgumentCaptor<CallSample> samples = ArgumentCaptor.forClass(CallSample.class);
        verify(w.telemetry, atLeast(100)).record(samples.capture());
        assertThat(samples.getAllValues()).allMatch(s -> s.provider() != null && s.jobId() != null && s.workflow() != null && s.durationMs() > 0);
        assertThat(samples.getAllValues().stream().map(CallSample::provider).distinct().count()).isGreaterThanOrEqualTo(3);
        assertThat(samples.getAllValues()).anyMatch(s -> !s.ok());
        assertThat(report.calls()).isGreaterThanOrEqualTo(100);

        // live states
        Job waiting = w.projection.get(report.waitingApproval()).orElseThrow();
        assertThat(waiting.workflow()).isEqualTo("mongodb-upgrade");
        assertThat(waiting.status()).isEqualTo(JobStatus.WAITING_APPROVAL);
        assertThat(waiting.pendingApproval("approval")).isNotNull();
        assertThat(waiting.resume().fromStep()).isEqualTo("approval");
        assertThat(waiting.step("approval").status()).isEqualTo(StepStatus.WAITING);
        assertThat(waiting.step("healthCheck").status()).isEqualTo(StepStatus.SUCCEEDED);

        Job attention = w.projection.get(report.needsAttention()).orElseThrow();
        assertThat(attention.workflow()).isEqualTo("vm-restart");
        assertThat(attention.status()).isEqualTo(JobStatus.NEEDS_ATTENTION);
        assertThat(attention.attention().step()).isEqualTo("validate");
        assertThat(attention.attention().error().errorClass()).isEqualTo("timeout");
        assertThat(attention.step("validate").status()).isEqualTo(StepStatus.FAILED);
        assertThat(attention.step("restart").status()).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(attention.approvals()).singleElement().satisfies(a -> assertThat(a.status()).isEqualTo(Approval.Status.APPROVED));
        assertThat(attention.resume().fromStep()).isEqualTo("validate");

        Job batch = w.projection.get(report.runningBatch()).orElseThrow();
        assertThat(batch.workflow()).isEqualTo("patch-cve");
        assertThat(batch.kind()).isEqualTo(Job.Kind.BATCH);
        assertThat(batch.status()).isEqualTo(JobStatus.RUNNING);
        // one row per ready VM of the registry; waves of 5%, 25% and 100% of six rows hold 1, 1 and 4
        List<String> vms = RunsTestWiring.READY.stream().filter(r -> r.type().equals("vm")).map(Resource::id).toList();
        assertThat(batch.batch().rows()).hasSize(vms.size());
        assertThat(batch.batch().rows()).extracting(r -> r.input().path("vmId").asText()).containsExactlyElementsOf(vms);
        assertThat(batch.batch().waves()).extracting(Wave::size).containsExactly(1, 1, 4);
        assertThat(batch.batch().waves().get(0).status()).isEqualTo("done");
        assertThat(batch.batch().waves().get(1).status()).isEqualTo("running");
        assertThat(batch.batch().waves().get(2).status()).isEqualTo("pending");
        assertThat(batch.batch().count("succeeded")).isEqualTo(1);
        assertThat(batch.batch().count("running")).isEqualTo(1);
        assertThat(batch.batch().count("pending")).isEqualTo(4);
        assertThat(batch.progress()).isEqualTo(new Job.Progress(1, 6, 0));
        List<Job> rows = w.projection.children(batch.id());
        assertThat(rows).hasSize(2);
        assertThat(rows.stream().filter(r -> r.status() == JobStatus.RUNNING)).singleElement()
                .satisfies(r -> assertThat(r.step("patch").status()).isEqualTo(StepStatus.RUNNING));

        // the two resumable live jobs were handed to the engine with their resume point
        ArgumentCaptor<Job> started = ArgumentCaptor.forClass(Job.class);
        verify(engine, org.mockito.Mockito.times(2)).start(started.capture());
        assertThat(started.getAllValues()).extracting(Job::id).containsExactlyInAnyOrder(waiting.id(), attention.id());
        assertThat(started.getAllValues()).allMatch(j -> j.resume() != null && j.definition() != null);

        // events were published for everything, so the audit and the streams see the same history
        assertThat(w.projection.history(waiting.id())).extracting(e -> e.data().path("event").asText())
                .contains("job.started", "job.step", "job.approval-requested");
        assertThat(w.projection.history(batch.id())).extracting(e -> e.data().path("event").asText()).contains("batch.wave");
        assertThat(w.projection.history(history.get(0).id())).extracting(e -> e.data().path("event").asText()).first().isEqualTo("job.started");
    }

    @Test
    void theSeedIsDeterministic() {
        RunsTestWiring a = new RunsTestWiring();
        RunsTestWiring b = new RunsTestWiring();
        PlatformProperties props = new PlatformProperties();
        DemoRunsSeeder.Report ra = new DemoRunsSeeder(props, a.workflows, a.providers, a.resources, a.projection, a.events, a.telemetry, RunsTestWiring.idleEngine(), a.clock).seed();
        DemoRunsSeeder.Report rb = new DemoRunsSeeder(props, b.workflows, b.providers, b.resources, b.projection, b.events, b.telemetry, RunsTestWiring.idleEngine(), b.clock).seed();
        assertThat(ra.failed()).isEqualTo(rb.failed());
        assertThat(ra.cancelled()).isEqualTo(rb.cancelled());
        assertThat(ra.calls()).isEqualTo(rb.calls());
        assertThat(a.projection.all().stream().map(Job::workflow).sorted().toList()).isEqualTo(b.projection.all().stream().map(Job::workflow).sorted().toList());
    }
}
