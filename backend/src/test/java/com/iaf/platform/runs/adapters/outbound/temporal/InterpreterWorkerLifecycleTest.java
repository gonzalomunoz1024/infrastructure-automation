package com.iaf.platform.runs.adapters.outbound.temporal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.iaf.platform.config.PlatformProperties;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.WorkerFactory;

/** The in-process worker starts once a server answers and never fails the application's start or stop when none does. */
class InterpreterWorkerLifecycleTest {

    @Test
    void withoutAReachableServerTheStartIsRetriedInTheBackgroundAndTheStopIsQuiet() {
        PlatformProperties.Temporal temporal = new PlatformProperties.Temporal();
        temporal.setTarget("127.0.0.1:1");
        WorkflowServiceStubs stubs = WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions.newBuilder().setTarget(temporal.getTarget()).build());
        try {
            WorkerFactory factory = WorkerFactory.newInstance(WorkflowClient.newInstance(stubs, WorkflowClientOptions.newBuilder().setNamespace("x").build()));
            factory.newWorker(temporal.getTaskQueue());
            InterpreterWorkerLifecycle lifecycle = new InterpreterWorkerLifecycle(factory, temporal);

            lifecycle.onReady();

            assertThat(lifecycle.polling()).isFalse();
            assertThat(factory.isStarted()).isFalse();
            lifecycle.destroy();
            // closed: no further attempt, and nothing was started
            assertThat(lifecycle.start()).isFalse();
            assertThat(factory.isStarted()).isFalse();
        } finally {
            stubs.shutdownNow();
        }
    }

    @Test
    void withAServerTheWorkerPollsUntilTheContextCloses() {
        TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance();
        try {
            PlatformProperties.Temporal temporal = new PlatformProperties.Temporal();
            WorkerFactory factory = WorkerFactory.newInstance(env.getWorkflowClient());
            factory.newWorker(temporal.getTaskQueue()).registerWorkflowImplementationTypes(InterpreterWorkflowImpl.class);
            InterpreterWorkerLifecycle lifecycle = new InterpreterWorkerLifecycle(factory, temporal);

            lifecycle.onReady();

            assertThat(lifecycle.polling()).isTrue();
            assertThat(factory.isStarted()).isTrue();
            lifecycle.destroy();
            assertThat(factory.isShutdown()).isTrue();
            assertThat(lifecycle.polling()).isFalse();
        } finally {
            env.close();
        }
    }
}
