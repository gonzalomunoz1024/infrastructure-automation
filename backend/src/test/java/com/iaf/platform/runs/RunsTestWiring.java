package com.iaf.platform.runs;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.common.ports.PolicyPort;
import com.iaf.platform.common.ports.PolicyPort.PolicyDecision;
import com.iaf.platform.common.ports.TelemetryPort;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.providers.ports.inbound.ProviderQueries;
import com.iaf.platform.releases.ports.inbound.ReleaseQueries;
import com.iaf.platform.resources.domain.Resource;
import com.iaf.platform.resources.ports.inbound.ResourceCommands;
import com.iaf.platform.resources.ports.inbound.ResourceCommands.RegisterResource;
import com.iaf.platform.resources.ports.inbound.ResourceQueries;
import com.iaf.platform.runs.adapters.outbound.ContentPriceLists;
import com.iaf.platform.runs.adapters.outbound.DemoAutomationRunner;
import com.iaf.platform.runs.adapters.outbound.InMemoryProjectionStore;
import com.iaf.platform.runs.adapters.outbound.InMemoryWorkflowExecution;
import com.iaf.platform.runs.adapters.outbound.MockServiceNowChangeTickets;
import com.iaf.platform.runs.adapters.outbound.RecordedProviderCall;
import com.iaf.platform.runs.application.AutomationRunners;
import com.iaf.platform.runs.application.JobEvents;
import com.iaf.platform.runs.application.JobProjection;
import com.iaf.platform.runs.application.RunsCommandHandler;
import com.iaf.platform.runs.application.RunsQueryHandler;
import com.iaf.platform.runs.application.StepExecutors;
import com.iaf.platform.runs.ports.outbound.ProjectionStore;
import com.iaf.platform.runs.ports.outbound.WorkflowExecutionPort;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowRecord;
import com.iaf.platform.workflows.domain.WorkflowStatus;
import com.iaf.platform.workflows.ports.inbound.WorkflowQueries;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The whole runs slice wired by hand: the other slices' ports are Mockito mocks answering from
 * the sample content, the slice's own components are real (projection, events, demo adapters,
 * the in-memory engine). Tests pick the engine (real or a mock) for the command handler.
 */
public final class RunsTestWiring {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    public final Clock clock = Clock.systemUTC();
    public final EventBus bus = new EventBus(clock);
    public final JobProjection projection;
    public final JobEvents events;
    public final WorkflowQueries workflows = mock(WorkflowQueries.class);
    public final ProviderQueries providers = mock(ProviderQueries.class);
    public final ResourceQueries resources = mock(ResourceQueries.class);
    public final ResourceCommands resourceCommands = mock(ResourceCommands.class);
    public final ReleaseQueries releases = mock(ReleaseQueries.class);
    public final PolicyPort policy = mock(PolicyPort.class);
    public final TelemetryPort telemetry = mock(TelemetryPort.class);
    public final MockServiceNowChangeTickets changes = new MockServiceNowChangeTickets();
    public final RecordedProviderCall calls;
    /** The repo's price lists ({@code kind: PriceList}). */
    public final ContentPriceLists prices;
    public final DemoAutomationRunner runner;
    public final AutomationRunners runners;
    public final StepExecutors executors;
    public final InMemoryWorkflowExecution execution;
    public final RunsQueryHandler queries;

    public RunsTestWiring() {
        this(new InMemoryProjectionStore());
    }

    /** The same wiring over another runs index, such as the JDBC one. */
    public RunsTestWiring(ProjectionStore store) {
        projection = new JobProjection(bus, store);
        projection.register();
        events = new JobEvents(bus, projection, clock);
        when(workflows.publishedAll()).thenAnswer(i -> Flux.fromIterable(SampleContent.workflows()).map(RunsTestWiring::record));
        when(workflows.published(anyString())).thenAnswer(i -> Mono.justOrEmpty(find(i.getArgument(0))).map(RunsTestWiring::record));
        when(workflows.version(anyString(), anyString())).thenAnswer(i -> Mono.justOrEmpty(find(i.getArgument(0)))
                .filter(d -> d.version().equals(i.getArgument(1))).map(RunsTestWiring::record));
        when(workflows.resolve(anyString(), anyString())).thenAnswer(i -> Mono.justOrEmpty(SampleContent.child(i.getArgument(0), i.getArgument(1)))
                .map(RunsTestWiring::record));
        when(providers.all()).thenAnswer(i -> Flux.fromIterable(SampleContent.providers()));
        when(providers.provider(anyString())).thenAnswer(i -> Mono.justOrEmpty(
                SampleContent.providers().stream().filter(p -> p.name().equals(i.getArgument(0))).findFirst()));
        when(resources.types()).thenAnswer(i -> Flux.fromIterable(SampleContent.types()));
        when(resources.get(anyString())).thenAnswer(i -> registry(i.getArgument(0)));
        when(resources.list(any(), any(), any())).thenAnswer(i -> Flux.fromIterable(READY)
                .filter(r -> i.getArgument(0) == null || r.type().equals(i.getArgument(0)))
                .filter(r -> i.getArgument(2) == null || r.status().equals(i.getArgument(2))));
        when(resources.use(any(), any(), any(), anyBoolean())).thenAnswer(i -> Mono.just(resource(i.getArgument(0), i.getArgument(1), "ready")));
        when(resources.lineage(anyString())).thenReturn(Mono.empty());
        when(resourceCommands.register(any())).thenAnswer(i -> {
            RegisterResource r = i.getArgument(0);
            String id = r.id() == null || r.id().isBlank() ? r.type() + "-new" : r.id();
            return Mono.just(new Resource(id, r.type(), id, r.state() == null ? "ready" : r.state(), r.attributes(), "team-x", "team",
                    new Resource.Origin(r.jobId(), r.workflow(), r.version()), r.builtFrom(), clock.instant(), clock.instant()));
        });
        when(releases.route(anyString(), anyString())).thenAnswer(i -> Mono.just(i.getArgument(1)));
        when(policy.evaluate(any())).thenReturn(Mono.just(PolicyDecision.allow("mock")));
        when(policy.engine()).thenReturn("mock");
        calls = new RecordedProviderCall(telemetry, projection, clock);
        PlatformProperties props = new PlatformProperties();
        props.getContent().setDir(SampleContent.ROOT.toString());
        prices = new ContentPriceLists(props);
        prices.load();
        runner = new DemoAutomationRunner(providers, telemetry, projection, clock);
        runners = new AutomationRunners(List.of(runner));
        executors = new StepExecutors(providers, calls, runners, workflows, resources, resourceCommands, events, policy, clock);
        execution = new InMemoryWorkflowExecution(executors, projection, events, changes, clock);
        queries = new RunsQueryHandler(projection, execution);
    }

    /** The command handler over the real in-memory engine. */
    public RunsCommandHandler commands() {
        return commands(execution);
    }

    public RunsCommandHandler commands(WorkflowExecutionPort engine) {
        return new RunsCommandHandler(workflows, providers, resources, releases, policy, changes, engine, projection, events, executors, prices, clock);
    }

    /** A mocked engine that accepts everything and holds nothing. */
    public static WorkflowExecutionPort idleEngine() {
        WorkflowExecutionPort engine = mock(WorkflowExecutionPort.class);
        when(engine.start(any())).thenReturn(Mono.empty());
        when(engine.isRunning(anyString())).thenReturn(Mono.just(false));
        when(engine.cancel(anyString(), anyString())).thenReturn(Mono.empty());
        when(engine.signalApproval(anyString(), anyString(), any())).thenReturn(Mono.empty());
        when(engine.signalAttention(anyString(), any())).thenReturn(Mono.empty());
        return engine;
    }

    public static Optional<WorkflowDefinition> find(String name) {
        return SampleContent.workflows().stream().filter(d -> d.name().equals(name)).findFirst();
    }

    public static WorkflowRecord record(WorkflowDefinition d) {
        return WorkflowRecord.of(d, WorkflowStatus.PUBLISHED, "workflows/" + d.name() + ".yaml", Instant.parse("2026-10-01T00:00:00Z"), "gonzalo")
                .published(Instant.parse("2026-10-01T00:00:00Z"), "gonzalo", null);
    }

    /** What the registry lists as ready: six VMs, two databases, an image, a volume and an environment. */
    public static final List<Resource> READY = List.of(
            resource("vm", "vm-201", "ready"), resource("vm", "vm-202", "ready"), resource("vm", "vm-203", "ready"),
            resource("vm", "vm-204", "ready"), resource("vm", "vm-205", "ready"), resource("vm", "vm-206", "ready"),
            resource("database", "db-201", "ready"), resource("database", "db-202", "ready"),
            resource("image", "img-201", "ready"), resource("volume", "vol-201", "ready"), resource("environment", "env-201", "ready"));

    /** The registry the tests see: ids that start with {@code missing-} are unknown, the prefix names the type. */
    static Mono<Resource> registry(String id) {
        if (id.startsWith("missing-")) {
            return Mono.empty();
        }
        String type = id.startsWith("db-") ? "database" : id.startsWith("img-") || id.startsWith("image-") ? "image"
                : id.startsWith("env-") ? "environment" : id.startsWith("vol-") ? "volume" : "vm";
        return Mono.just(resource(type, id, id.startsWith("retired-") ? "retired" : "ready"));
    }

    public static Resource resource(String type, String id, String status) {
        ObjectNode attributes = F.objectNode();
        attributes.put("name", "name-of-" + id);
        attributes.put("environment", "dev");
        return new Resource(id, type, "name-of-" + id, status, attributes, "team-compute", "team", null, List.of(),
                Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-01T00:00:00Z"));
    }

    /** Wait for a condition the engine reaches on another thread. */
    public static void await(Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not reached within " + timeout);
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted");
            }
        }
    }

    public static ObjectNode json(String... keyValues) {
        ObjectNode o = F.objectNode();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            o.put(keyValues[i], keyValues[i + 1]);
        }
        return o;
    }
}
