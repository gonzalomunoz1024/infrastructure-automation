package com.iaf.platform.observability.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;
import com.iaf.platform.common.domain.Effect;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.common.tracing.TestTracing;
import com.iaf.platform.common.tracing.Traces;
import com.iaf.platform.observability.application.JobTracing;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.providers.domain.ProviderDefinition.Action;
import com.iaf.platform.providers.domain.ProviderDefinition.Metadata;
import com.iaf.platform.providers.domain.ProviderDefinition.Spec;
import com.iaf.platform.runs.ports.outbound.ProviderCallPort;
import com.iaf.platform.runs.ports.outbound.ProviderCallPort.ProviderCall;
import com.iaf.platform.runs.ports.outbound.ProviderCallPort.ProviderCallException;

import io.micrometer.observation.Observation;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import reactor.core.publisher.Mono;

class TracedProviderCallsTest {

    static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");
    static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    static final JsonNodeFactory F = JsonNodeFactory.instance;
    static final Action RESET = new Action("power-cycle", null, null, "POST", "/vms/{id}/reset", Effect.CHANGE, false, false, null, null, null,
            null, null, null, null, null, null);
    static final ProviderDefinition VSPHERE = new ProviderDefinition("iaf.platform/v1", new Metadata("vsphere", null, "team-compute", "1.0.0", null),
            new Spec(null, null, Map.of(), null, List.of(RESET), Map.of(), List.of(), null, null, null, null, null, null, null));

    TestTracing tracing = TestTracing.on();
    EventBus bus = new EventBus(Clock.fixed(NOW, ZoneOffset.UTC));
    JobTracing jobs = new JobTracing(bus, tracing.traces, Clock.fixed(NOW, ZoneOffset.UTC));
    TracedProviderCalls processor = processor(tracing.traces, jobs);

    static TracedProviderCalls processor(Traces traces, JobTracing jobs) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("traces", traces);
        beans.addBean("jobTracing", jobs);
        return new TracedProviderCalls(beans.getBeanProvider(Traces.class), beans.getBeanProvider(JobTracing.class));
    }

    @AfterEach
    void close() {
        tracing.close();
    }

    /** A provider adapter with a method of its own besides the port's. */
    public static class Answering implements ProviderCallPort {
        @Override
        public Mono<JsonNode> call(ProviderCall call) {
            if ("fail".equals(call.input().path("mode").asText())) {
                return Mono.error(new ProviderCallException("timeout", "upstream said: token=s3cr3t", true, 504));
            }
            return Mono.just(F.objectNode().put("ok", true));
        }

        public String world() {
            return "recorded";
        }
    }

    static ProviderCall call(String jobId, String mode) {
        return new ProviderCall(jobId, 1, VSPHERE, RESET, "prod", F.objectNode().put("mode", mode), false);
    }

    static String attr(SpanData s, String key) {
        return s.getAttributes().get(AttributeKey.stringKey(key));
    }

    void startedWithARunningStep() {
        jobs.subscribe();
        bus.publish("job.started", "job", "job_1", "ana", F.objectNode().put("jobId", "job_1").put("workflow", "vm-restart").put("traceId", TRACE));
        bus.publish("job.step", "job", "job_1", "system", F.objectNode().put("jobId", "job_1").put("step", "restart").put("type", "provider")
                .put("status", "running").put("attempt", 1));
    }

    @Test
    void theProviderCallBeansAreProxiedAndKeepTheirOwnClass() {
        Object proxied = processor.postProcessAfterInitialization(new Answering(), "answering");
        assertThat(proxied).isInstanceOf(Answering.class).isInstanceOf(ProviderCallPort.class);
        assertThat(((Answering) proxied).world()).isEqualTo("recorded");
        ProviderCallPort lambda = c -> Mono.just(F.objectNode());
        Object proxiedLambda = processor.postProcessAfterInitialization(lambda, "lambda");
        assertThat(proxiedLambda).isInstanceOf(ProviderCallPort.class).isNotSameAs(lambda);
        Object other = new Object();
        assertThat(processor.postProcessAfterInitialization(other, "other")).isSameAs(other);

        ((ProviderCallPort) proxiedLambda).call(call(null, "ok")).block();
        assertThat(tracing.named("call vsphere.power-cycle")).hasSize(1);
    }

    @Test
    void aCallHangsUnderTheStepRunningOnTheCallingThread() {
        startedWithARunningStep();
        ProviderCallPort port = (ProviderCallPort) processor.postProcessAfterInitialization(new Answering(), "answering");

        assertThat(port.call(call("job_1", "ok")).block().path("ok").asBoolean()).isTrue();

        SpanData span = tracing.one("call vsphere.power-cycle");
        assertThat(span.getTraceId()).isEqualTo(TRACE);
        assertThat(attr(span, "provider")).isEqualTo("vsphere");
        assertThat(attr(span, "action")).isEqualTo("power-cycle");
        assertThat(attr(span, "environment")).isEqualTo("prod");
        assertThat(attr(span, "job.id")).isEqualTo("job_1");
        assertThat(attr(span, Traces.ERROR_CLASS)).isEqualTo(Traces.NONE);
        Observation step = jobs.parentOf("job_1");
        assertThat(span.getParentSpanId()).isEqualTo(tracing.traces.spanId(step));
    }

    @Test
    void aFailedCallRecordsTheProvidersErrorClassAndNeverItsMessage() {
        startedWithARunningStep();
        ProviderCallPort port = (ProviderCallPort) processor.postProcessAfterInitialization(new Answering(), "answering");

        assertThat(port.call(call("job_1", "fail")).onErrorResume(e -> Mono.empty()).block()).isNull();

        SpanData span = tracing.one("call vsphere.power-cycle");
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(attr(span, Traces.ERROR_CLASS)).isEqualTo("timeout");
        assertThat(span.toString()).doesNotContain("s3cr3t");
    }

    @Test
    void outsideAJobTheCallHangsUnderTheRequestAndIsTheCurrentObservationDownstream() {
        Observation request = tracing.traces.start("request", null, null, Map.of(), Map.of());
        ProviderCallPort echo = c -> Mono.deferContextual(ctx -> Mono.just((JsonNode) TextNode.valueOf(tracing.traces.spanId(Traces.current(ctx)))));
        ProviderCallPort port = (ProviderCallPort) processor.postProcessAfterInitialization(echo, "echo");

        String downstream = port.call(call(null, "ok")).contextWrite(c -> Traces.with(c, request)).block().asText();
        request.stop();

        SpanData span = tracing.one("call vsphere.power-cycle");
        assertThat(downstream).isEqualTo(span.getSpanId());
        assertThat(span.getParentSpanId()).isEqualTo(tracing.one("request").getSpanId());
    }
}
