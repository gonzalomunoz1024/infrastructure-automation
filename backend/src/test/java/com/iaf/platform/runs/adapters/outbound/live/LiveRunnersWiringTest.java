package com.iaf.platform.runs.adapters.outbound.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.http.codec.CodecsAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.reactive.function.client.WebClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;

import com.iaf.platform.common.ports.ArtifactStorePort;
import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.common.ports.GitRemotePort;
import com.iaf.platform.common.ports.TelemetryPort;
import com.iaf.platform.config.PlaybooksProperties;
import com.iaf.platform.config.RunnersProperties;
import com.iaf.platform.providers.ports.inbound.ProviderQueries;
import com.iaf.platform.runs.adapters.outbound.DemoAutomationRunner;
import com.iaf.platform.runs.application.AutomationRunners;
import com.iaf.platform.runs.application.JobProjection;
import com.iaf.platform.workflows.domain.StepType;

/** The live runners replace the demo runner for their step types only when the live profile is on. */
class LiveRunnersWiringTest {

    ReactiveWebApplicationContextRunner context() {
        return new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class, JacksonAutoConfiguration.class,
                        CodecsAutoConfiguration.class, WebClientAutoConfiguration.class))
                .withUserConfiguration(RunnersProperties.class, PlaybooksProperties.class, AnsibleRunnerAutomation.class, TerraformCliAutomation.class,
                        ProcessScriptRunner.class, HttpStepRunner.class, DemoAutomationRunner.class, AutomationRunners.class)
                .withBean(GitRemotePort.class, () -> mock(GitRemotePort.class))
                .withBean(ArtifactStorePort.class, () -> mock(ArtifactStorePort.class))
                .withBean(CredentialsPort.class, () -> mock(CredentialsPort.class))
                .withBean(ProviderQueries.class, () -> mock(ProviderQueries.class))
                .withBean(TelemetryPort.class, () -> mock(TelemetryPort.class))
                .withBean(JobProjection.class, () -> mock(JobProjection.class))
                .withBean(Clock.class, Clock::systemUTC);
    }

    @Test
    void theLiveProfileRunsAnsibleTerraformScriptsAndHttpForReal() {
        context().withPropertyValues("spring.profiles.active=live", "platform.runners.container-runtime=podman",
                "platform.runners.terraform.backend=s3", "platform.runners.terraform.backend-config.bucket=tf-state",
                "platform.runners.script.images.python=registry.example.com/python:3.12").run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    AutomationRunners runners = ctx.getBean(AutomationRunners.class);
                    assertThat(runners.runnerFor(StepType.ANSIBLE)).containsInstanceOf(AnsibleRunnerAutomation.class);
                    assertThat(runners.runnerFor(StepType.TERRAFORM)).containsInstanceOf(TerraformCliAutomation.class);
                    assertThat(runners.runnerFor(StepType.SCRIPT)).containsInstanceOf(ProcessScriptRunner.class);
                    assertThat(runners.runnerFor(StepType.HTTP)).containsInstanceOf(HttpStepRunner.class);
                    assertThat(runners.runnerFor(StepType.IDP)).containsInstanceOf(DemoAutomationRunner.class);
                    assertThat(runners.runnerFor(StepType.NOTIFY)).containsInstanceOf(DemoAutomationRunner.class);
                    RunnersProperties props = ctx.getBean(RunnersProperties.class);
                    assertThat(props.getContainerRuntime()).isEqualTo("podman");
                    assertThat(props.getTerraform().getBackendConfig()).containsEntry("bucket", "tf-state");
                    assertThat(props.getScript().getImages()).containsEntry("python", "registry.example.com/python:3.12")
                            .containsEntry("go", "golang:1.24-alpine");
                });
    }

    @Test
    void withoutItTheDemoRunnerAnswersEveryAutomationStep() {
        context().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(AnsibleRunnerAutomation.class).doesNotHaveBean(TerraformCliAutomation.class)
                    .doesNotHaveBean(ProcessScriptRunner.class).doesNotHaveBean(HttpStepRunner.class);
            AutomationRunners runners = ctx.getBean(AutomationRunners.class);
            for (StepType type : new StepType[] {StepType.ANSIBLE, StepType.TERRAFORM, StepType.SCRIPT, StepType.HTTP}) {
                assertThat(runners.runnerFor(type)).containsInstanceOf(DemoAutomationRunner.class);
            }
        });
    }
}
