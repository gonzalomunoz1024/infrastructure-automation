package com.iaf.provider.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.ActivityCanceledException;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.testing.TestActivityEnvironment;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testserver.TestServer;
import io.temporal.worker.WorkerFactory;

class ProviderWorkerTest {

    private TestWorkflowEnvironment env;
    private final Inventory inventory = new Inventory();

    @BeforeEach
    void start() {
        env = TestWorkflowEnvironment.newInstance();
        env.newWorker(PlatformCall.QUEUE).registerWorkflowImplementationTypes(PlatformCall.Impl.class);
    }

    @AfterEach
    void stop() {
        env.close();
    }

    private Provider serve(Provider provider) {
        ProviderWorker.attach(env.newWorker(provider.taskQueue()), provider);
        env.start();
        return provider;
    }

    private PlatformCall caller() {
        return env.getWorkflowClient().newWorkflowStub(PlatformCall.class, WorkflowOptions.newBuilder()
                .setTaskQueue(PlatformCall.QUEUE)
                .setWorkflowExecutionTimeout(Duration.ofMinutes(1))
                .build());
    }

    private JsonNode call(Provider provider, String action, Object input, long heartbeatMillis) {
        return caller().call(provider.taskQueue(), action, Envelope.of(input).toJson(), heartbeatMillis);
    }

    private static Provider probe(Handler<JsonNode, JsonNode> handler) {
        return Provider.named("probe").owner("team-platform").version("1.0.0")
                .action(Action.json("probe.run", handler).effect(Effect.READ))
                .build();
    }

    @Test
    void aCallOnTheProvidersQueueRunsTheActionAndAnswersItsOutput() {
        Provider p = serve(inventory.provider().build());

        JsonNode outcome = call(p, "item.get", Map.of("id", "it-1"), 0);

        assertThat(outcome.path("output").toString()).isEqualTo("{\"id\":\"it-1\",\"name\":\"first\",\"state\":\"active\"}");
    }

    @Test
    void aFatalFailureReachesThePlatformAsANonRetryableFailureOfItsClass() {
        Provider p = serve(inventory.provider().build());

        JsonNode outcome = call(p, "item.get", Map.of("id", "it-404"), 0);

        assertThat(outcome.path("type").asText()).isEqualTo("not_found");
        assertThat(outcome.path("nonRetryable").asBoolean()).isTrue();
        assertThat(outcome.path("message").asText()).isEqualTo("No item it-404");
    }

    @Test
    void aRetryableFailureReachesThePlatformAsARetryableFailureOfItsClass() {
        Provider p = serve(probe((ctx, in) -> {
            throw ctx.retryable("busy", "The inventory is busy");
        }));

        JsonNode outcome = call(p, "probe.run", Map.of(), 0);

        assertThat(outcome.path("type").asText()).isEqualTo("busy");
        assertThat(outcome.path("nonRetryable").asBoolean()).isFalse();
    }

    @Test
    void aBrokenContractIsFatal() {
        Provider p = serve(inventory.provider().build());

        JsonNode badInput = call(p, "item.get", Map.of("name", "no id"), 0);
        JsonNode unknown = call(p, "item.melt", Map.of(), 0);

        assertThat(badInput.path("type").asText()).isEqualTo("invalid_input");
        assertThat(badInput.path("nonRetryable").asBoolean()).isTrue();
        assertThat(unknown.path("type").asText()).isEqualTo("unknown_action");
        assertThat(unknown.path("nonRetryable").asBoolean()).isTrue();
    }

    @Test
    void heartbeatsKeepALongCallAlivePastTheHeartbeatTimeout() {
        Provider p = serve(probe((ctx, in) -> {
            for (int i = 0; i < 20; i++) {
                Thread.sleep(100);
                ctx.heartbeat(i);
            }
            return in;
        }));

        JsonNode outcome = call(p, "probe.run", Map.of("a", 1), 1000);

        assertThat(outcome.path("output").toString()).isEqualTo("{\"a\":1}");
    }

    @Test
    void aCallThatStopsHeartbeatingTimesOutOnItsHeartbeat() {
        Provider p = serve(probe((ctx, in) -> {
            Thread.sleep(2500);
            return in;
        }));

        JsonNode outcome = call(p, "probe.run", Map.of(), 1000);

        assertThat(outcome.path("timeout").asText()).isEqualTo("TIMEOUT_TYPE_HEARTBEAT");
    }

    @Test
    void cancellingTheStepStopsTheCallAtItsNextHeartbeat() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        AtomicBoolean sawCancel = new AtomicBoolean();
        Provider p = serve(probe((ctx, in) -> {
            running.countDown();
            try {
                for (int i = 0; i < 200; i++) {
                    Thread.sleep(50);
                    ctx.heartbeat(null);
                }
            } catch (ActivityCanceledException e) {
                sawCancel.set(true);
                throw e;
            }
            return in;
        }));
        PlatformCall wf = caller();
        WorkflowClient.start(wf::call, p.taskQueue(), "probe.run", Envelope.of(Map.of()).toJson(), 1000L);
        assertThat(running.await(10, TimeUnit.SECONDS)).isTrue();

        wf.cancel();
        JsonNode outcome = WorkflowStub.fromTyped(wf).getResult(JsonNode.class);

        assertThat(outcome.path("cancelled").asBoolean()).isTrue();
        assertThat(sawCancel).isTrue();
    }

    @Test
    void aWorkerOnAnotherQueueIsRefused() {
        Provider p = inventory.provider().build();

        assertThatThrownBy(() -> ProviderWorker.attach(env.newWorker("somewhere-else"), p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provider-inventory");
    }

    @ActivityInterface
    public interface ProbeRun {
        @ActivityMethod(name = "probe.run")
        JsonNode run(JsonNode envelope);
    }

    @Test
    void heartbeatDetailsReachTemporal() {
        TestActivityEnvironment activities = TestActivityEnvironment.newInstance();
        try {
            List<String> details = new CopyOnWriteArrayList<>();
            activities.registerActivitiesImplementations(ProviderWorker.activities(probe((ctx, in) -> {
                ctx.heartbeat("scanned 10 of 20");
                return in;
            })));
            activities.setActivityHeartbeatListener(String.class, details::add);
            ProbeRun stub = activities.newActivityStub(ProbeRun.class, ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(10))
                    .setHeartbeatTimeout(Duration.ofSeconds(5))
                    .build());

            JsonNode out = stub.run(Envelope.of(Map.of("a", 1)).toJson());

            assertThat(out.toString()).isEqualTo("{\"a\":1}");
            assertThat(details).containsExactly("scanned 10 of 20");
        } finally {
            activities.close();
        }
    }

    @Test
    void stoppingTheWorkerLetsTheCallInFlightFinish() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Provider p = probe((ctx, in) -> {
            running.countDown();
            release.await();
            return in;
        });
        try (TestServer.PortBoundTestServer server = TestServer.createPortBoundServer(port)) {
            WorkflowServiceStubs stubs = WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions.newBuilder().setTarget("127.0.0.1:" + port).build());
            WorkflowClient client = WorkflowClient.newInstance(stubs);
            WorkerFactory platform = WorkerFactory.newInstance(client);
            platform.newWorker(PlatformCall.QUEUE).registerWorkflowImplementationTypes(PlatformCall.Impl.class);
            platform.start();
            ProviderWorker worker = ProviderWorker.start(p, new WorkerSettings("127.0.0.1:" + port, "default", Duration.ofSeconds(20)));
            try {
                PlatformCall wf = client.newWorkflowStub(PlatformCall.class, WorkflowOptions.newBuilder().setTaskQueue(PlatformCall.QUEUE).build());
                WorkflowClient.start(wf::call, p.taskQueue(), "probe.run", Envelope.of(Map.of("a", 1)).toJson(), 0L);
                assertThat(running.await(20, TimeUnit.SECONDS)).isTrue();

                Thread stopping = Thread.ofPlatform().start(worker::shutdown);
                Thread.sleep(300);
                assertThat(stopping.isAlive()).as("the worker drains the call in flight").isTrue();
                release.countDown();
                stopping.join(20_000);

                assertThat(stopping.isAlive()).isFalse();
                assertThat(WorkflowStub.fromTyped(wf).getResult(JsonNode.class).path("output").toString()).isEqualTo("{\"a\":1}");
            } finally {
                release.countDown();
                worker.close();
                platform.shutdownNow();
                stubs.shutdownNow();
            }
        }
    }
}
