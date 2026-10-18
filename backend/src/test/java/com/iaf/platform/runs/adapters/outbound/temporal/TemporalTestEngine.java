package com.iaf.platform.runs.adapters.outbound.temporal;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import com.iaf.platform.common.events.DomainEvent;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.runs.RunsTestWiring;
import com.iaf.platform.runs.SampleContent;
import com.iaf.platform.runs.application.AutomationRunners;
import com.iaf.platform.runs.application.JobFactory;
import com.iaf.platform.runs.application.StepExecutors;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.runs.domain.JobStatus;
import com.iaf.platform.runs.domain.Outcome;
import com.iaf.platform.runs.ports.outbound.ProviderCallPort;
import com.iaf.platform.runs.ports.outbound.StepTraces;
import com.iaf.platform.testing.TestWeb;
import com.iaf.platform.workflows.domain.WorkflowDefinition;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import reactor.core.publisher.Mono;

/**
 * The Temporal engine over the runs test wiring: Temporal's in-process test server (time
 * skipping on), one worker on a test task queue running {@link InterpreterWorkflowImpl} and the
 * real {@link StepActivitiesImpl} over the wiring's ports, projection and event bus, and the
 * {@link TemporalWorkflowExecution} port on the test server's client.
 *
 * <p>The test server only skips time while a client blocks on a workflow's result (and never while
 * an activity runs), so a test polls the projection while the job waits for a person and calls
 * {@link #result} once nothing is left to signal: the timers of the waits, the approval and
 * attention timeouts and the retry backoffs then pass in no time.</p>
 */
final class TemporalTestEngine implements AutoCloseable {

    static final String QUEUE = "interpreter-test";
    // generous: the test server shares the machine with whole builds
    static final Duration WAIT = Duration.ofSeconds(60);

    final RunsTestWiring w;
    final TestWorkflowEnvironment env;
    final Worker worker;
    final WorkflowClient client;
    final TemporalWorkflowExecution execution;
    final List<DomainEvent> published = new CopyOnWriteArrayList<>();

    /** How the engine is built: each field has the value the plain constructor uses. */
    static final class Options {
        RunsTestWiring wiring = new RunsTestWiring();
        UnaryOperator<ProviderCallPort> calls = UnaryOperator.identity();
        UnaryOperator<AutomationRunners> runners = UnaryOperator.identity();
        Consumer<PlatformProperties.Temporal> config = temporal -> {
        };
        Consumer<TestWorkflowEnvironment> workers = env -> {
        };
        StepTraces traces = StepTraces.UNTRACED;
        PayloadEncryption codec;

        /** The runs wiring the engine works over (one built over another projection store, say). */
        Options wiring(RunsTestWiring wiring) {
            this.wiring = wiring;
            return this;
        }

        /** Wraps the recorded provider port, to make some calls fail or slow. */
        Options calls(UnaryOperator<ProviderCallPort> calls) {
            this.calls = calls;
            return this;
        }

        /** Wraps the wiring's automation runners, to put a slow or failing runner in front. */
        Options runners(UnaryOperator<AutomationRunners> runners) {
            this.runners = runners;
            return this;
        }

        /** Adjusts {@code platform.temporal.*} (the heartbeats) before the worker starts. */
        Options config(Consumer<PlatformProperties.Temporal> config) {
            this.config = config;
            return this;
        }

        /** Registers the teams' workers on their own task queues before the test server starts. */
        Options workers(Consumer<TestWorkflowEnvironment> workers) {
            this.workers = workers;
            return this;
        }

        /** What tells a worker call the {@code traceparent} of its step's span (the tracing slice's spans, say). */
        Options traces(StepTraces traces) {
            this.traces = traces;
            return this;
        }

        /** Encrypts the payloads of the history with this base64 key, as {@code platform.temporal.payload-key} does. */
        Options payloadKey(String base64) {
            this.codec = PayloadEncryption.of(base64, QUEUE);
            return this;
        }
    }

    TemporalTestEngine() {
        this(new Options());
    }

    /** @param calls wraps the recorded provider port, to make some calls fail */
    TemporalTestEngine(UnaryOperator<ProviderCallPort> calls) {
        this(new Options().calls(calls));
    }

    /**
     * @param calls   wraps the recorded provider port, to make some calls fail or slow
     * @param config  adjusts {@code platform.temporal.*} (the heartbeats) before the worker starts
     * @param workers registers the teams' workers on their own task queues before the test server starts
     */
    TemporalTestEngine(UnaryOperator<ProviderCallPort> calls, Consumer<PlatformProperties.Temporal> config, Consumer<TestWorkflowEnvironment> workers) {
        this(new Options().calls(calls).config(config).workers(workers));
    }

    TemporalTestEngine(Options options) {
        w = options.wiring;
        w.bus.register(published::add);
        StepExecutors executors = new StepExecutors(w.providers, options.calls.apply(w.calls), options.runners.apply(w.runners), w.workflows, w.resources,
                w.resourceCommands, w.events, w.policy, w.clock);
        PlatformProperties props = new PlatformProperties();
        props.getTemporal().setTaskQueue(QUEUE);
        props.getExecution().setMode("temporal");
        options.config.accept(props.getTemporal());
        env = TestWorkflowEnvironment.newInstance(TestEnvironmentOptions.newBuilder()
                .setWorkflowClientOptions(WorkflowClientOptions.newBuilder().setDataConverter(TemporalConfig.dataConverter(TestWeb.json(), options.codec)).build())
                .build());
        worker = env.newWorker(QUEUE, TemporalConfig.workerOptions(props.getTemporal()));
        worker.registerWorkflowImplementationTypes(TemporalConfig.workflowOptions(props.getTemporal()), InterpreterWorkflowImpl.class);
        worker.registerActivitiesImplementations(new StepActivitiesImpl(executors, w.projection, w.events, w.changes, w.telemetry, options.traces, w.clock, props));
        options.workers.accept(env);
        env.start();
        client = env.getWorkflowClient();
        execution = new TemporalWorkflowExecution(client, props);
    }

    /** A provider port whose calls of one action take {@code delay} longer, like a slow system. */
    static ProviderCallPort slow(ProviderCallPort delegate, String action, Duration delay) {
        return call -> call.action() != null && action.equals(call.action().id()) ? delegate.call(call).delayElement(delay) : delegate.call(call);
    }

    /** Replay a history recorded elsewhere (a resource file) against the workflow code. */
    void replay(WorkflowExecutionHistory history) {
        try {
            worker.replayWorkflowExecution(history);
        } catch (Exception e) {
            throw new AssertionError("the recorded history does not replay: " + e, e);
        }
    }

    /** A provider port that fails the first {@code times} calls of one action as unavailable, then answers like {@code delegate}. */
    static ProviderCallPort failing(ProviderCallPort delegate, String action, int times) {
        AtomicInteger left = new AtomicInteger(times);
        return call -> call.action() != null && action.equals(call.action().id()) && left.getAndDecrement() > 0
                ? Mono.error(new ProviderCallPort.ProviderCallException("unavailable", "the provider is down", true, 503))
                : delegate.call(call);
    }

    /** Start a job the way the command handler does: into the projection, {@code job.started}, then the engine. */
    Job start(WorkflowDefinition def, Map<String, Object> input) {
        return start(JobFactory.newJob(def, def.version(), SampleContent.json(input), SampleContent.GONZALO, "rest", null, null, null, null, null,
                w.clock.instant()));
    }

    Job start(Job job) {
        w.projection.put(job);
        w.events.started(job);
        execution.start(job).block();
        return job;
    }

    Job job(String id) {
        return w.projection.get(id).orElseThrow();
    }

    void awaitStatus(String id, JobStatus status) {
        RunsTestWiring.await(WAIT, () -> job(id).status() == status);
    }

    /** The workflow's own view, through its queries. */
    InterpreterWorkflow workflow(String id) {
        return client.newWorkflowStub(InterpreterWorkflow.class, id);
    }

    void awaitWorkflowStatus(String id, JobStatus status) {
        RunsTestWiring.await(WAIT, () -> workflow(id).status() == status);
    }

    /** Block until the job's workflow ends; the test server skips over its timers meanwhile. */
    Outcome result(String id) {
        try {
            return client.newUntypedWorkflowStub(id).getResult(WAIT.toSeconds(), TimeUnit.SECONDS, Outcome.class);
        } catch (TimeoutException e) {
            throw new AssertionError("job " + id + " did not end within " + WAIT, e);
        }
    }

    /** Replay the job's recorded history against the workflow code; throws when the code took another path (not deterministic). */
    void replay(String jobId) {
        try {
            worker.replayWorkflowExecution(client.fetchHistory(jobId));
        } catch (Exception e) {
            throw new AssertionError("job " + jobId + " does not replay: " + e, e);
        }
    }

    List<String> types(String jobId) {
        return published.stream().filter(e -> e.aggregateId().equals(jobId)).map(DomainEvent::type).toList();
    }

    List<DomainEvent> events(String jobId, String type) {
        return published.stream().filter(e -> e.aggregateId().equals(jobId) && e.type().equals(type)).toList();
    }

    DomainEvent event(String jobId, String type) {
        return events(jobId, type).stream().findFirst().orElseThrow(() -> new AssertionError("no " + type + " for " + jobId));
    }

    /** {@code step:status} of every {@code job.step} event of a job, in publishing order. */
    List<String> steps(String jobId) {
        return events(jobId, "job.step").stream().map(e -> e.data().path("step").asText() + ":" + e.data().path("status").asText()).toList();
    }

    @Override
    public void close() {
        env.close();
    }
}
