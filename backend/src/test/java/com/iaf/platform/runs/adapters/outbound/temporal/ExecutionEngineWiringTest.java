package com.iaf.platform.runs.adapters.outbound.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.common.ports.PolicyPort;
import com.iaf.platform.common.ports.TelemetryPort;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.config.Role;
import com.iaf.platform.providers.ports.inbound.ProviderQueries;
import com.iaf.platform.releases.ports.inbound.ReleaseQueries;
import com.iaf.platform.resources.ports.inbound.ResourceCommands;
import com.iaf.platform.resources.ports.inbound.ResourceQueries;
import com.iaf.platform.runs.adapters.outbound.InMemoryWorkflowExecution;
import com.iaf.platform.runs.application.RunsCommandHandler;
import com.iaf.platform.common.redaction.SecretValues;
import com.iaf.platform.notifications.ports.inbound.NotificationCommands;
import com.iaf.platform.runs.ports.outbound.WorkflowExecutionPort;
import com.iaf.platform.workflows.ports.inbound.WorkflowQueries;

import io.temporal.client.WorkflowClient;
import io.temporal.common.converter.DataConverter;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;

/**
 * Which engine runs the jobs: the in-memory one unless the {@code temporal} profile is active (the
 * default demo profile needs no Temporal at all), the Temporal one with it, and a context with the
 * profile comes up even when no Temporal server answers (the worker only polls once the
 * application is ready). The other slices' ports are mocked.
 */
class ExecutionEngineWiringTest {

    @Configuration
    @ComponentScan(basePackageClasses = {RunsCommandHandler.class, InMemoryWorkflowExecution.class})
    static class RunsSlice {
    }

    @Configuration
    static class NoBeans {
    }

    private static ConfigurableApplicationContext configOnly(String... profiles) {
        return new SpringApplicationBuilder(NoBeans.class).web(WebApplicationType.NONE).bannerMode(Banner.Mode.OFF).logStartupInfo(false)
                .profiles(profiles).run();
    }

    private static ApplicationContextRunner runner(PlatformProperties props) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .withUserConfiguration(RunsSlice.class)
                .withBean(PlatformProperties.class, () -> props)
                .withBean(Clock.class, Clock::systemUTC)
                .withBean(EventBus.class, () -> new EventBus(Clock.systemUTC()))
                .withBean(WorkflowQueries.class, () -> mock(WorkflowQueries.class))
                .withBean(ProviderQueries.class, () -> mock(ProviderQueries.class))
                .withBean(ResourceQueries.class, () -> mock(ResourceQueries.class))
                .withBean(ResourceCommands.class, () -> mock(ResourceCommands.class))
                .withBean(ReleaseQueries.class, () -> mock(ReleaseQueries.class))
                .withBean(PolicyPort.class, () -> mock(PolicyPort.class))
                .withBean(TelemetryPort.class, () -> mock(TelemetryPort.class))
                .withBean(NotificationCommands.class, () -> mock(NotificationCommands.class))
                .withBean(SecretValues.class, SecretValues::new);
    }

    @Test
    void withoutTheTemporalProfileJobsRunInMemoryAndNothingTemporalIsCreated() {
        runner(new PlatformProperties()).withPropertyValues("spring.profiles.active=demo").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(WorkflowExecutionPort.class)).isInstanceOf(InMemoryWorkflowExecution.class);
            assertThat(ctx).doesNotHaveBean(TemporalWorkflowExecution.class);
            assertThat(ctx).doesNotHaveBean(TemporalConfig.class);
            assertThat(ctx).doesNotHaveBean(StepActivities.class);
            assertThat(ctx).doesNotHaveBean(WorkflowClient.class);
            assertThat(ctx).doesNotHaveBean(WorkerFactory.class);
        });
    }

    @Test
    void withTheTemporalProfileJobsRunOnTemporalAndTheContextNeedsNoServer() {
        PlatformProperties props = new PlatformProperties();
        props.getExecution().setMode("temporal");
        props.getTemporal().setTarget("127.0.0.1:1");
        props.getTemporal().setNamespace("platform-test");
        runner(props).withPropertyValues("spring.profiles.active=demo,temporal").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(WorkflowExecutionPort.class)).isInstanceOf(TemporalWorkflowExecution.class);
            assertThat(ctx.getBean(RunsCommandHandler.class)).isNotNull();
            assertThat(ctx).hasSingleBean(StepActivities.class);
            assertThat(ctx).hasSingleBean(DataConverter.class);
            assertThat(ctx.getBean(WorkflowClient.class).getOptions().getNamespace()).isEqualTo("platform-test");
            assertThat(ctx.getBean(Worker.class).getTaskQueue()).isEqualTo("platform-interpreter");
            // polling starts on ApplicationReadyEvent, which a bare context never publishes
            assertThat(ctx.getBean(WorkerFactory.class).isStarted()).isFalse();
        });
    }

    @Test
    void anApiOnlyInstanceStartsJobsOnTemporalWithoutAWorker() {
        PlatformProperties props = new PlatformProperties();
        props.getExecution().setMode("temporal");
        props.getTemporal().setTarget("127.0.0.1:1");
        runner(props).withPropertyValues("spring.profiles.active=demo,temporal", "platform.roles=api").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(WorkflowExecutionPort.class)).isInstanceOf(TemporalWorkflowExecution.class);
            assertThat(ctx).hasSingleBean(WorkflowClient.class);
            assertThat(ctx).doesNotHaveBean(Worker.class);
            assertThat(ctx).doesNotHaveBean(InterpreterWorkerLifecycle.class);
        });
    }

    @Test
    void aWorkerOnlyInstancePollsTheInterpretersTaskQueue() {
        PlatformProperties props = new PlatformProperties();
        props.getExecution().setMode("temporal");
        props.getTemporal().setTarget("127.0.0.1:1");
        runner(props).withPropertyValues("spring.profiles.active=demo,temporal", "platform.roles=worker").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(Worker.class).getTaskQueue()).isEqualTo("platform-interpreter");
            assertThat(ctx).hasSingleBean(InterpreterWorkerLifecycle.class);
        });
    }

    @Test
    void applicationYmlSetsTheExecutionModeWithTheTemporalProfile() {
        try (ConfigurableApplicationContext ctx = configOnly()) {
            assertThat(ctx.getEnvironment().getProperty("platform.execution.mode")).isEqualTo("in-memory");
        }
        try (ConfigurableApplicationContext ctx = configOnly("demo", "temporal")) {
            assertThat(ctx.getEnvironment().getProperty("platform.execution.mode")).isEqualTo("temporal");
            assertThat(ctx.getEnvironment().getProperty("platform.temporal.target")).isEqualTo("127.0.0.1:7233");
            assertThat(ctx.getEnvironment().getProperty("platform.temporal.task-queue")).isEqualTo("platform-interpreter");
            assertThat(Role.of(ctx.getEnvironment())).containsExactly(Role.API, Role.WORKER);
        }
    }
}
