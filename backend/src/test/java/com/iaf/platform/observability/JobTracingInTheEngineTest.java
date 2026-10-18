package com.iaf.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import com.iaf.platform.common.tracing.TestTracing;
import com.iaf.platform.common.tracing.Traces;
import com.iaf.platform.observability.adapters.outbound.TracedProviderCalls;
import com.iaf.platform.observability.application.JobTracing;
import com.iaf.platform.runs.RunsTestWiring;
import com.iaf.platform.runs.SampleContent;
import com.iaf.platform.runs.adapters.outbound.InMemoryWorkflowExecution;
import com.iaf.platform.runs.application.JobFactory;
import com.iaf.platform.runs.application.StepExecutors;
import com.iaf.platform.runs.domain.Approval;
import com.iaf.platform.runs.domain.ApprovalDecision;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.runs.domain.JobStatus;
import com.iaf.platform.runs.ports.outbound.ProviderCallPort;
import com.iaf.platform.workflows.domain.WorkflowDefinition;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;

/** A real job on the in-memory engine, traced: the job, each step and each provider call, in the job's own trace. */
class JobTracingInTheEngineTest {

    static final Duration WAIT = Duration.ofSeconds(15);

    TestTracing tracing = TestTracing.on();
    RunsTestWiring w = new RunsTestWiring();

    @AfterEach
    void close() {
        tracing.close();
    }

    @Test
    void aJobsStepsAndProviderCallsAreSpansInTheTraceItsTraceIdNames() {
        JobTracing jobs = new JobTracing(w.bus, tracing.traces, w.clock);
        jobs.subscribe();
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("traces", tracing.traces);
        beans.addBean("jobTracing", jobs);
        TracedProviderCalls processor = new TracedProviderCalls(beans.getBeanProvider(Traces.class), beans.getBeanProvider(JobTracing.class));
        ProviderCallPort calls = (ProviderCallPort) processor.postProcessAfterInitialization(w.calls, "recordedProviderCall");
        StepExecutors executors = new StepExecutors(w.providers, calls, w.runners, w.workflows, w.resources, w.resourceCommands, w.events, w.clock);
        InMemoryWorkflowExecution engine = new InMemoryWorkflowExecution(executors, w.projection, w.events, w.changes, w.clock);

        WorkflowDefinition def = SampleContent.workflow("vm-restart");
        Job job = JobFactory.newJob(def, def.version(), SampleContent.json(Map.of("vmId", "vm-1042", "reason", "stuck")), SampleContent.GONZALO,
                "rest", null, null, null, null, null, w.clock.instant());
        w.projection.put(job);
        w.events.started(job);
        engine.start(job).block();
        RunsTestWiring.await(WAIT, () -> w.projection.get(job.id()).orElseThrow().status() == JobStatus.WAITING_APPROVAL);
        Approval a = w.projection.get(job.id()).orElseThrow().pendingApproval("approval");
        engine.signalApproval(job.id(), a.id(), ApprovalDecision.approved("gonzalo", "go", Instant.now())).block();
        RunsTestWiring.await(WAIT, () -> tracing.spans.stream().anyMatch(s -> s.getName().equals("job vm-restart")));

        assertThat(job.traceId()).matches("[0-9a-f]{32}");
        SpanData root = tracing.one("job vm-restart");
        assertThat(root.getTraceId()).isEqualTo(job.traceId());
        assertThat(root.getParentSpanContext().isValid()).isFalse();
        assertThat(root.getAttributes().get(AttributeKey.stringKey("status"))).isEqualTo("succeeded");
        assertThat(tracing.spans).allMatch(s -> s.getTraceId().equals(job.traceId()));

        List<SpanData> steps = tracing.spans.stream().filter(s -> s.getName().startsWith("step ")).toList();
        assertThat(steps).extracting(SpanData::getName)
                .contains("step check-vm-exists", "step no-backup-running", "step approval", "step restart", "step validate", "step notify-done");
        assertThat(steps).allMatch(s -> s.getParentSpanId().equals(root.getSpanId()));
        assertThat(tracing.one("step approval").getEvents()).extracting(e -> e.getName()).contains("waiting");

        Set<String> stepIds = steps.stream().map(SpanData::getSpanId).collect(Collectors.toSet());
        List<SpanData> providerCalls = tracing.spans.stream().filter(s -> s.getName().startsWith("call ")).toList();
        assertThat(providerCalls).isNotEmpty().allMatch(s -> stepIds.contains(s.getParentSpanId()));
        assertThat(providerCalls).allMatch(s -> job.id().equals(s.getAttributes().get(AttributeKey.stringKey("job.id"))));
    }
}
