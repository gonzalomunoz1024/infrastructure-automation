package com.iaf.platform.observability.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.prometheus.PrometheusMetricsExportAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.iaf.platform.common.tracing.Traces;
import com.iaf.platform.runs.ports.outbound.ProviderCallPort;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import reactor.core.publisher.Mono;

/**
 * Wiring: with the actuator's metrics and the platform's own {@code application.yml}, the span of
 * each provider call is also a timer in {@code /actuator/prometheus}, tagged with the provider,
 * the action, the environment, whether it was a dry run and its error class, with the histogram
 * buckets the dashboard's p95 reads (from 10 ms to an hour, as {@code application.yml} sets them).
 */
class ProviderCallMetricsTest {

    static final String TIMER = TracedProviderCalls.SPAN.replace('.', '_') + "_seconds";
    static final Pattern LE = Pattern.compile("le=\"([0-9.eE+-]+)\"");

    @Test
    void eachCallIsATimerWithItsOutcomeAndTheBucketsForItsP95() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(ObservationAutoConfiguration.class, MetricsAutoConfiguration.class,
                        CompositeMeterRegistryAutoConfiguration.class, PrometheusMetricsExportAutoConfiguration.class))
                .withBean(Traces.class)
                .withBean(TracedProviderCalls.class)
                .withBean(TracedProviderCallsTest.Answering.class)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    ProviderCallPort port = ctx.getBean(ProviderCallPort.class);
                    port.call(TracedProviderCallsTest.call("job_1", "ok")).block();
                    port.call(TracedProviderCallsTest.call("job_2", "ok")).block();
                    port.call(TracedProviderCallsTest.call("job_3", "fail")).onErrorResume(e -> Mono.empty()).block();

                    List<String> scrape = Arrays.asList(ctx.getBean(PrometheusMeterRegistry.class).scrape().split("\n"));

                    for (String suffix : List.of("_bucket", "_count", "_sum", "_max")) {
                        assertThat(scrape).as(TIMER + suffix).anyMatch(l -> l.startsWith(TIMER + suffix + "{"));
                    }
                    assertThat(series(scrape, "_count", "none")).singleElement().satisfies(l -> assertThat(l).matches(".* 2(\\.0)?"));
                    assertThat(series(scrape, "_count", "timeout")).singleElement().satisfies(l -> assertThat(l).matches(".* 1(\\.0)?"));
                    List<Double> buckets = series(scrape, "_bucket", "none").stream().map(LE::matcher).filter(Matcher::find)
                            .map(m -> Double.parseDouble(m.group(1))).toList();
                    assertThat(buckets).as("finite buckets").hasSizeGreaterThan(10);
                    assertThat(buckets.get(0)).as("the smallest bucket").isGreaterThanOrEqualTo(0.01);
                    assertThat(buckets.get(buckets.size() - 1)).as("the largest finite bucket").isGreaterThanOrEqualTo(3600.0);
                });
    }

    /** The lines of {@code TIMER + suffix} for vsphere's power-cycle in prod, not a dry run, with this error class. */
    static List<String> series(List<String> scrape, String suffix, String errorClass) {
        return scrape.stream().filter(l -> l.startsWith(TIMER + suffix + "{"))
                .filter(l -> l.contains("provider=\"vsphere\"") && l.contains("action=\"power-cycle\"") && l.contains("environment=\"prod\"")
                        && l.contains("dry_run=\"false\"") && l.contains("error_class=\"" + errorClass + "\""))
                .toList();
    }
}
