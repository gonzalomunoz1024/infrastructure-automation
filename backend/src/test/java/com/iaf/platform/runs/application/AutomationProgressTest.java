package com.iaf.platform.runs.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.iaf.platform.common.events.DomainEvent;
import com.iaf.platform.runs.RunsTestWiring;
import com.iaf.platform.runs.SampleContent;
import com.iaf.platform.runs.adapters.outbound.InMemoryWorkflowExecution;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.runs.domain.JobStatus;
import com.iaf.platform.runs.ports.outbound.AutomationRunnerPort;
import com.iaf.platform.workflows.domain.StepType;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowYaml;

import reactor.core.publisher.Mono;

/** An automation runner's progress reaches the job log, and cancelling the job stops a running step. */
class AutomationProgressTest {

    static final Duration WAIT = Duration.ofSeconds(15);
    static final WorkflowDefinition DEF = WorkflowYaml.parse("""
            apiVersion: iaf.platform/v1
            kind: Workflow
            metadata: { name: t-long, title: Long, noun: thing, verb: run, owner: team-x, version: 1.0.0 }
            workflow:
              do:
                - call:
                    name: Long call
                    call: http
                    with: { method: GET, url: "https://example.com/slow" }
                - after:
                    name: After
                    notify: { channel: "#x", message: "done" }
            """);

    /** Reports one line, then runs until it is cancelled (or answers at once when {@code finish} is set). */
    static final class SlowRunner implements AutomationRunnerPort {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicReference<RunRequest> seen = new AtomicReference<>();
        volatile boolean finish;

        @Override
        public boolean supports(StepType type) {
            return type == StepType.HTTP;
        }

        @Override
        public Mono<JsonNode> run(RunRequest request) {
            return run(request, Progress.NONE);
        }

        @Override
        public Mono<JsonNode> run(RunRequest request, Progress progress) {
            seen.set(request);
            progress.report("GET https://example.com/slow");
            if (finish) {
                return Mono.just(JsonNodeFactory.instance.objectNode().put("status", 200));
            }
            return Mono.<JsonNode>never().doOnCancel(() -> cancelled.set(true));
        }
    }

    RunsTestWiring w;
    SlowRunner slow;
    InMemoryWorkflowExecution execution;
    final List<DomainEvent> published = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        w = new RunsTestWiring();
        w.bus.register(published::add);
        slow = new SlowRunner();
        StepExecutors executors = new StepExecutors(w.providers, w.calls, new AutomationRunners(List.of(slow, w.runner)), w.workflows,
                w.resources, w.resourceCommands, w.events, w.policy, w.clock);
        execution = new InMemoryWorkflowExecution(executors, w.projection, w.events, w.changes, w.clock);
    }

    Job start() {
        Job job = JobFactory.newJob(DEF, DEF.version(), SampleContent.json(Map.of()), SampleContent.GONZALO, "rest", null, null, null, null, null, w.clock.instant());
        w.projection.put(job);
        w.events.started(job);
        execution.start(job).block();
        return job;
    }

    List<String> logs(String jobId) {
        return published.stream().filter(e -> e.type().equals("job.log") && e.aggregateId().equals(jobId))
                .map(e -> e.data().path("message").asText()).toList();
    }

    @Test
    void progressLinesGoToTheJobLogWithTheStepId() {
        slow.finish = true;
        Job job = start();

        RunsTestWiring.await(WAIT, () -> w.projection.get(job.id()).orElseThrow().status() == JobStatus.SUCCEEDED);

        assertThat(logs(job.id())).contains("call: GET https://example.com/slow");
        assertThat(slow.seen.get().dryRun()).isFalse();
    }

    @Test
    void cancellingTheJobStopsTheRunningStep() {
        Job job = start();
        RunsTestWiring.await(WAIT, () -> logs(job.id()).contains("call: GET https://example.com/slow"));

        execution.cancel(job.id(), "mateo").block();

        RunsTestWiring.await(WAIT, () -> w.projection.get(job.id()).orElseThrow().status() == JobStatus.CANCELLED);
        RunsTestWiring.await(WAIT, slow.cancelled::get);
        assertThat(w.projection.get(job.id()).orElseThrow().step("after")).isNull();
    }
}
