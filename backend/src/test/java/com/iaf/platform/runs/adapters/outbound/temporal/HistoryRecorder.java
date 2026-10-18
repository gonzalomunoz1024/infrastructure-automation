package com.iaf.platform.runs.adapters.outbound.temporal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import com.iaf.platform.providers.domain.ProviderYaml;
import com.iaf.platform.runs.RunsTestWiring;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowYaml;

import reactor.core.publisher.Mono;

/**
 * Records the histories the replay fixtures under {@code src/test/resources/temporal} are made
 * of: a scenario runs on the test server with the workflow code as it is <em>now</em>, and its
 * histories are written as JSON. Run it before a change that alters the commands the workflow
 * issues, keep the files, and replay them in a test gated like the change itself:
 *
 * <pre>mvn -o test -Dtest=HistoryRecorder -Diaf.record.histories=/abs/dir -Dsurefire.failIfNoSpecifiedTests=false</pre>
 *
 * <p>Nothing runs without the property, so the suite never rewrites a fixture by accident.</p>
 */
class HistoryRecorder {

    static final String PROPERTY = "iaf.record.histories";

    static final WorkflowDefinition WAIT = WorkflowYaml.parse("""
            apiVersion: iaf.platform/v1
            kind: Workflow
            metadata: { name: t-wait, title: Wait, noun: thing, verb: wait, owner: team-x, version: 1.0.0 }
            workflow:
              do:
                - pause:
                    name: Pause
                    wait: { duration: 10m }
                - after:
                    name: After
                    notify: { channel: "#x", message: "after the wait" }
            """);

    static final WorkflowDefinition PARENT = WorkflowYaml.parse("""
            apiVersion: iaf.platform/v1
            kind: Workflow
            metadata: { name: t-parent, title: Parent, noun: thing, verb: run, owner: team-x, version: 1.0.0 }
            workflow:
              do:
                - child:
                    name: Child
                    run: { workflow: t-wait, version: "^1" }
                - after:
                    name: After
                    notify: { channel: "#x", message: "after the child" }
            """);

    /** A run step whose child is a wait: the parent's and the child's histories. */
    @Test
    @EnabledIfSystemProperty(named = PROPERTY, matches = ".+")
    void runStepWithAChild() throws IOException {
        Path dir = Path.of(System.getProperty(PROPERTY));
        try (TemporalTestEngine t = new TemporalTestEngine()) {
            when(t.w.workflows.resolve(eq("t-wait"), anyString())).thenReturn(Mono.just(RunsTestWiring.record(WAIT)));
            Job job = t.start(PARENT, Map.of());
            t.result(job.id());
            Job child = t.w.projection.children(job.id()).get(0);
            t.result(child.id());
            write(dir.resolve("run-step-parent.json"), t.client.fetchHistory(job.id()).toJson(true));
            write(dir.resolve("run-step-child.json"), t.client.fetchHistory(child.id()).toJson(true));
        }
    }

    /** A step on a team worker (an sdk provider's action) and a {@code call: activity}: the job's history. */
    @Test
    @EnabledIfSystemProperty(named = PROPERTY, matches = ".+")
    void teamWorkerSteps() throws IOException {
        Path dir = Path.of(System.getProperty(PROPERTY));
        TeamWorkerStepsTest.FakeTeamWorker team = new TeamWorkerStepsTest.FakeTeamWorker();
        try (TemporalTestEngine t = new TemporalTestEngine(UnaryOperator.identity(), temporal -> {
        }, env -> {
            env.newWorker(TeamWorkerStepsTest.QUEUE).registerActivitiesImplementations(team);
            env.newWorker(TeamWorkerStepsTest.ACTIVITIES).registerActivitiesImplementations(team);
        })) {
            when(t.w.providers.provider(eq("reports"))).thenReturn(Mono.just(ProviderYaml.parse(TeamWorkerStepsTest.PROVIDER)));
            Job job = t.start(WorkflowYaml.parse(TeamWorkerStepsTest.FLOW), Map.of("id", "vm-1042"));
            t.result(job.id());
            write(dir.resolve("team-steps.json"), t.client.fetchHistory(job.id()).toJson(true));
        }
    }

    static final WorkflowDefinition POLLS = WorkflowYaml.parse("""
            apiVersion: iaf.platform/v1
            kind: Workflow
            metadata: { name: t-polls, title: Polls, noun: thing, verb: poll, owner: team-x, version: 1.0.0 }
            workflow:
              do:
                - get:
                    name: Get
                    call: provider
                    with: { provider: vsphere, action: vm.get, input: { id: "{{ $input.id }}" } }
                - ready:
                    name: Ready
                    use: { type: vm, id: vm-201, waitUntil: ready }
                - never:
                    name: Never
                    wait: { until: "{{ $('Get').json.powerState == 'poweredOff' }}" }
                    timeout: 1m
                    pauseOnFailure: false
            """);

    /** A {@code use} that polls until the registry says ready and a {@code wait until} that polls to its timeout. */
    @Test
    @EnabledIfSystemProperty(named = PROPERTY, matches = ".+")
    void pollingSteps() throws IOException {
        Path dir = Path.of(System.getProperty(PROPERTY));
        try (TemporalTestEngine t = new TemporalTestEngine()) {
            AtomicInteger lookups = new AtomicInteger();
            when(t.w.resources.use(any(), any(), any(), anyBoolean())).thenAnswer(i -> Mono.just(
                    RunsTestWiring.resource(i.getArgument(0), i.getArgument(1), lookups.incrementAndGet() < 3 ? "building" : "ready")));
            Job job = t.start(POLLS, Map.of("id", "vm-1042"));
            t.result(job.id());
            write(dir.resolve("polls.json"), t.client.fetchHistory(job.id()).toJson(true));
        }
    }

    static final WorkflowDefinition CALL = WorkflowYaml.parse("""
            apiVersion: iaf.platform/v1
            kind: Workflow
            metadata: { name: t-call, title: Call, noun: thing, verb: call, owner: team-x, version: 1.0.0 }
            workflow:
              do:
                - get:
                    name: Get
                    call: provider
                    with: { provider: vsphere, action: vm.get, input: { id: "{{ $input.id }}" } }
                - after:
                    name: After
                    notify: { channel: "#x", message: "after {{ $('Get').json.name }}" }
            """);

    /** A job cancelled while a platform call runs: the call runs to its end, then the job unwinds. */
    @Test
    @EnabledIfSystemProperty(named = PROPERTY, matches = ".+")
    void cancelDuringACall() throws IOException {
        Path dir = Path.of(System.getProperty(PROPERTY));
        try (TemporalTestEngine t = new TemporalTestEngine(calls -> TemporalTestEngine.slow(calls, "vm.get", Duration.ofSeconds(3)))) {
            Job job = t.start(CALL, Map.of("id", "vm-1042"));
            RunsTestWiring.await(TemporalTestEngine.WAIT, () -> t.job(job.id()).step("get") != null);
            t.execution.cancel(job.id(), "mateo").block();
            t.result(job.id());
            write(dir.resolve("cancel-during-call.json"), t.client.fetchHistory(job.id()).toJson(true));
        }
    }

    /** A malformed wait duration on a step that retries: an error the interpreter retried. */
    static final WorkflowDefinition LEAF_ERROR = WorkflowYaml.parse("""
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
            """);

    /** A fork whose first branch waits inside a try while the second fails outside any. */
    static final WorkflowDefinition FORK_GUARD = WorkflowYaml.parse("""
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
            """);

    /** The interpreter's failure paths as they ran before the interpreter-rules change. */
    @Test
    @EnabledIfSystemProperty(named = PROPERTY, matches = ".+")
    void interpreterRules() throws IOException {
        Path dir = Path.of(System.getProperty(PROPERTY));
        try (TemporalTestEngine t = new TemporalTestEngine()) {
            Job leaf = t.start(LEAF_ERROR, Map.of());
            t.result(leaf.id());
            write(dir.resolve("leaf-error-retried.json"), t.client.fetchHistory(leaf.id()).toJson(true));

            Job fork = t.start(FORK_GUARD, Map.of());
            t.result(fork.id());
            write(dir.resolve("fork-guarded-sibling.json"), t.client.fetchHistory(fork.id()).toJson(true));
        }
    }

    private static void write(Path file, String json) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }
}
