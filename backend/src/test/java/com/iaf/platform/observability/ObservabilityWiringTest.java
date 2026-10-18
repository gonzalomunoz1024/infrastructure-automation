package com.iaf.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.common.ports.TelemetryPort;
import com.iaf.platform.common.tracing.Traces;
import com.iaf.platform.observability.adapters.outbound.TracedProviderCalls;
import com.iaf.platform.observability.application.JobTracing;
import com.iaf.platform.runs.adapters.outbound.RecordedProviderCall;
import com.iaf.platform.runs.application.JobProjection;
import com.iaf.platform.runs.ports.outbound.ProviderCallPort;

/** The observability slice wires as beans, and the provider call bean it proxies stays injectable by its port and its class. */
class ObservabilityWiringTest {

    @Configuration
    @ComponentScan(basePackageClasses = {JobTracing.class, TracedProviderCalls.class, Traces.class})
    static class Slice {
    }

    @Test
    void theSliceWiresAndProxiesTheProviderCallBean() {
        new ApplicationContextRunner()
                .withUserConfiguration(Slice.class)
                .withBean(Clock.class, Clock::systemUTC)
                .withBean(EventBus.class, () -> new EventBus(Clock.systemUTC()))
                .withBean(JobProjection.class, () -> mock(JobProjection.class))
                .withBean(TelemetryPort.class, () -> mock(TelemetryPort.class))
                .withBean(RecordedProviderCall.class)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(JobTracing.class);
                    assertThat(ctx).hasSingleBean(Traces.class);
                    ProviderCallPort port = ctx.getBean(ProviderCallPort.class);
                    assertThat(AopUtils.isAopProxy(port)).isTrue();
                    assertThat(ctx.getBean(RecordedProviderCall.class)).isSameAs(port);
                    assertThat(ctx.getBean(RecordedProviderCall.class).world()).isNotNull();
                });
    }
}
