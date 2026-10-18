package com.iaf.provider.sdk;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.activity.DynamicActivity;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.converter.EncodedValues;
import io.temporal.failure.ApplicationFailure;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;

/**
 * The Temporal worker of a provider: it polls the provider's task queue and runs each call the
 * platform schedules there, dispatching by activity type (the action id). A classified failure
 * goes back as an {@code ApplicationFailure} whose type is the error class, non-retryable when it
 * is fatal. Stopping is graceful: polling stops, the calls in flight get the drain timeout to
 * finish.
 *
 * <pre>
 * try (ProviderWorker worker = ProviderWorker.start(provider)) {
 *     worker.awaitShutdown();          // until SIGTERM / Ctrl-C
 * }
 * </pre>
 */
public final class ProviderWorker implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ProviderWorker.class);

    private final Provider provider;
    private final WorkflowServiceStubs stubs;
    private final WorkerFactory factory;
    private final Duration drainTimeout;
    private final CountDownLatch stopped = new CountDownLatch(1);
    private volatile boolean stopping;

    private ProviderWorker(Provider provider, WorkflowServiceStubs stubs, WorkerFactory factory, Duration drainTimeout) {
        this.provider = provider;
        this.stubs = stubs;
        this.factory = factory;
        this.drainTimeout = drainTimeout;
    }

    /** Connect with the settings of the environment ({@link WorkerSettings#fromEnvironment()}) and start polling. */
    public static ProviderWorker start(Provider provider) {
        return start(provider, WorkerSettings.fromEnvironment());
    }

    /**
     * Connect with these settings (TLS and an API key when they say so) and start polling. A TLS
     * file that cannot be read fails here, naming its variable.
     */
    public static ProviderWorker start(Provider provider, WorkerSettings settings) {
        WorkflowServiceStubs stubs = WorkflowServiceStubs.newServiceStubs(TemporalConnection.options(settings));
        WorkflowClient client = WorkflowClient.newInstance(stubs, WorkflowClientOptions.newBuilder().setNamespace(settings.namespace()).build());
        WorkerFactory factory = WorkerFactory.newInstance(client);
        attach(factory.newWorker(provider.taskQueue()), provider);
        factory.start();
        log.info("provider {} {} serves {} action(s) on task queue {} at {} (namespace {}, {})", provider.name(), provider.version(), provider.actions().size(),
                provider.taskQueue(), settings.address(), settings.namespace(), TemporalConnection.describe(settings));
        return new ProviderWorker(provider, stubs, factory, settings.drainTimeout());
    }

    /**
     * Serve the provider's actions on a worker you built yourself: your own client (interceptors,
     * a data converter) or a test environment. The worker must listen on {@link Provider#taskQueue()}.
     */
    public static Worker attach(Worker worker, Provider provider) {
        if (!provider.taskQueue().equals(worker.getTaskQueue())) {
            throw new IllegalArgumentException("Provider " + provider.name() + " is served on " + provider.taskQueue() + ", not on " + worker.getTaskQueue());
        }
        worker.registerActivitiesImplementations(activities(provider));
        return worker;
    }

    /**
     * The activity implementation behind {@link #attach}: one dynamic activity that serves every
     * action of the provider. Register it on a {@code TestActivityEnvironment} to test handlers
     * the way Temporal runs them.
     */
    public static DynamicActivity activities(Provider provider) {
        return new Calls(new Dispatcher(provider));
    }

    public Provider provider() {
        return provider;
    }

    /** Block until the process is asked to stop (SIGTERM, Ctrl-C) and the worker has drained. */
    public void awaitShutdown() throws InterruptedException {
        Thread hook = new Thread(this::shutdown, "provider-worker-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        stopped.await();
    }

    /** Stop polling and give the calls in flight the drain timeout to finish; idempotent. */
    public synchronized void shutdown() {
        if (stopping) {
            return;
        }
        stopping = true;
        log.info("provider {} stops polling and drains for up to {}", provider.name(), drainTimeout);
        factory.shutdown();
        try {
            factory.awaitTermination(drainTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!factory.isTerminated()) {
                log.warn("provider {}: calls still running after {}; stopping them", provider.name(), drainTimeout);
                factory.shutdownNow();
                factory.awaitTermination(5, TimeUnit.SECONDS);
            }
        } finally {
            stubs.shutdown();
            stopped.countDown();
        }
    }

    @Override
    public void close() {
        shutdown();
    }

    /** One dynamic activity for every action: the activity type picks the handler. */
    static final class Calls implements DynamicActivity {

        private final Dispatcher dispatcher;

        Calls(Dispatcher dispatcher) {
            this.dispatcher = dispatcher;
        }

        @Override
        public Object execute(EncodedValues args) {
            ActivityExecutionContext ctx = Activity.getExecutionContext();
            String type = ctx.getInfo().getActivityType();
            JsonNode payload = args.getSize() == 0 ? null : args.get(0, JsonNode.class);
            try {
                return dispatcher.dispatch(type, payload, ctx::heartbeat);
            } catch (ProviderException e) {
                throw ApplicationFailure.newBuilder()
                        .setMessage(e.getMessage())
                        .setType(e.errorClass())
                        .setNonRetryable(!e.retryable())
                        .build();
            }
        }
    }
}
