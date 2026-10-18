package com.iaf.platform.observability.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.events.DomainEvent;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.common.tracing.TestTracing;
import com.iaf.platform.common.tracing.Traces;

import io.micrometer.observation.Observation;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.SpanData;

class JobTracingTest {

    static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");
    static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    static final JsonNodeFactory F = JsonNodeFactory.instance;

    TestTracing tracing = TestTracing.on();
    EventBus bus = new EventBus(Clock.fixed(NOW, ZoneOffset.UTC));
    JobTracing jobs = new JobTracing(bus, tracing.traces, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void subscribe() {
        jobs.subscribe();
    }

    @AfterEach
    void close() {
        tracing.close();
    }

    static String attr(SpanData s, String key) {
        return s.getAttributes().get(AttributeKey.stringKey(key));
    }

    void started(String jobId, String workflow, String traceId, String parentId) {
        ObjectNode d = F.objectNode().put("jobId", jobId).put("workflow", workflow).put("version", "1.2.0").put("channel", "rest")
                .put("kind", "single").put("traceId", traceId);
        if (parentId != null) {
            d.put("parentId", parentId);
        }
        bus.publish("job.started", "job", jobId, "ana", d);
    }

    void step(String jobId, String step, String type, String status, int attempt, String errorClass) {
        ObjectNode d = F.objectNode().put("jobId", jobId).put("step", step).put("name", step).put("type", type).put("status", status)
                .put("attempt", attempt);
        if (errorClass != null) {
            d.putObject("error").put("class", errorClass).put("message", "the provider said: token=s3cr3t").put("retryable", true);
        }
        bus.publish("job.step", "job", jobId, "system", d);
    }

    void finished(String jobId, String status, String errorClass) {
        ObjectNode d = F.objectNode().put("jobId", jobId).put("workflow", "vm-restart").put("version", "1.2.0").put("status", status)
                .put("durationMs", 10);
        if (errorClass != null) {
            d.put("errorClass", errorClass);
        }
        bus.publish("job.finished", "job", jobId, "system", d);
    }

    @Test
    void aJobIsTheRootOfTheTraceItsTraceIdNamesWithASpanPerStepAttempt() {
        started("job_1", "vm-restart", TRACE, null);
        step("job_1", "restart", "provider", "running", 1, null);
        step("job_1", "restart", "provider", "failed", 1, "timeout");
        step("job_1", "restart", "provider", "running", 2, null);
        step("job_1", "restart", "provider", "succeeded", 2, null);
        step("job_1", "approve", "approval", "running", 1, null);
        step("job_1", "approve", "approval", "waiting", 1, null);
        step("job_1", "approve", "approval", "succeeded", 1, null);
        step("job_1", "cleanup", "set", "skipped", 0, null);
        finished("job_1", "succeeded", null);

        SpanData job = tracing.one("job vm-restart");
        assertThat(job.getTraceId()).isEqualTo(TRACE);
        assertThat(job.getParentSpanContext().isValid()).isFalse();
        assertThat(attr(job, "workflow")).isEqualTo("vm-restart");
        assertThat(attr(job, "job.id")).isEqualTo("job_1");
        assertThat(attr(job, "status")).isEqualTo("succeeded");
        List<SpanData> restarts = tracing.named("step restart");
        assertThat(restarts).hasSize(2).allMatch(s -> s.getParentSpanId().equals(job.getSpanId()) && s.getTraceId().equals(TRACE));
        SpanData failed = restarts.stream().filter(s -> "1".equals(attr(s, "attempt"))).findFirst().orElseThrow();
        assertThat(failed.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(attr(failed, Traces.ERROR_CLASS)).isEqualTo("timeout");
        assertThat(attr(failed, "status")).isEqualTo("failed");
        assertThat(failed.toString()).doesNotContain("s3cr3t");
        SpanData retried = restarts.stream().filter(s -> "2".equals(attr(s, "attempt"))).findFirst().orElseThrow();
        assertThat(attr(retried, "status")).isEqualTo("succeeded");
        assertThat(attr(retried, Traces.ERROR_CLASS)).isEqualTo(Traces.NONE);
        SpanData approve = tracing.one("step approve");
        assertThat(approve.getEvents()).extracting(e -> e.getName()).contains("waiting");
        assertThat(attr(approve, "step.type")).isEqualTo("approval");
        assertThat(attr(tracing.one("step cleanup"), "status")).isEqualTo("skipped");
        assertThat(jobs.openJobs()).isZero();
    }

    @Test
    void stepsNestUnderTheirContainerAndAChildJobUnderTheRunStepThatStartedIt() {
        started("job_p", "deploy-app", TRACE, null);
        step("job_p", "steps", "try", "running", 1, null);
        step("job_p", "deploy", "run", "running", 1, null);
        started("job_c", "vm-create", TRACE, "job_p");
        step("job_c", "create", "provider", "running", 1, null);
        step("job_c", "create", "provider", "succeeded", 1, null);
        finished("job_c", "succeeded", null);
        step("job_p", "deploy", "run", "succeeded", 1, null);
        step("job_p", "steps", "try", "succeeded", 1, null);
        finished("job_p", "succeeded", null);

        SpanData container = tracing.one("step steps");
        SpanData deploy = tracing.one("step deploy");
        SpanData child = tracing.one("job vm-create");
        assertThat(deploy.getParentSpanId()).isEqualTo(container.getSpanId());
        assertThat(child.getParentSpanId()).isEqualTo(deploy.getSpanId());
        assertThat(child.getTraceId()).isEqualTo(TRACE);
        assertThat(tracing.one("step create").getParentSpanId()).isEqualTo(child.getSpanId());
    }

    @Test
    void aBranchStepOnAThreadOfItsOwnHangsUnderItsFork() throws Exception {
        started("job_f", "fan-out", TRACE, null);
        step("job_f", "both", "fork", "running", 1, null);
        CompletableFuture.runAsync(() -> {
            step("job_f", "left", "provider", "running", 1, null);
            step("job_f", "left", "provider", "succeeded", 1, null);
        }).get();
        step("job_f", "both", "fork", "succeeded", 1, null);
        finished("job_f", "succeeded", null);

        assertThat(tracing.one("step left").getParentSpanId()).isEqualTo(tracing.one("step both").getSpanId());
    }

    @Test
    void aJobStartedInsideARequestHangsUnderTheRequestSpan() {
        Observation request = tracing.traces.start("request", null, null, Map.of(), Map.of());
        String trace = tracing.traces.traceId(request);
        tracing.traces.remember(trace, request);
        started("job_r", "vm-restart", trace, null);
        tracing.traces.forget(trace, request);
        request.stop();
        finished("job_r", "failed", "timeout");

        SpanData job = tracing.one("job vm-restart");
        assertThat(job.getParentSpanId()).isEqualTo(tracing.one("request").getSpanId());
        assertThat(job.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(attr(job, Traces.ERROR_CLASS)).isEqualTo("timeout");
        assertThat(attr(job, "status")).isEqualTo("failed");
    }

    @Test
    void aBatchRowHangsUnderItsBatch() {
        started("job_b", "vm-restart", TRACE, null);
        started("job_row", "vm-restart", TRACE, "job_b");
        finished("job_row", "succeeded", null);
        finished("job_b", "succeeded", null);

        assertThat(tracing.named("job vm-restart")).hasSize(2);
        SpanData batch = tracing.named("job vm-restart").stream().filter(s -> "job_b".equals(attr(s, "job.id"))).findFirst().orElseThrow();
        SpanData row = tracing.named("job vm-restart").stream().filter(s -> "job_row".equals(attr(s, "job.id"))).findFirst().orElseThrow();
        assertThat(row.getParentSpanId()).isEqualTo(batch.getSpanId());
        assertThat(attr(row, "job.parent")).isEqualTo("job_b");
    }

    @Test
    void cancellingAJobEndsTheStepsStillOpen() {
        started("job_x", "vm-restart", TRACE, null);
        step("job_x", "approve", "approval", "running", 1, null);
        step("job_x", "approve", "approval", "waiting", 1, null);
        bus.publish("job.cancelled", "job", "job_x", "ana", F.objectNode().put("jobId", "job_x").put("by", "ana"));

        assertThat(attr(tracing.one("step approve"), "status")).isEqualTo("cancelled");
        assertThat(attr(tracing.one("job vm-restart"), "status")).isEqualTo("cancelled");
        assertThat(jobs.openJobs()).isZero();
    }

    @Test
    void historyAndReplayedStepsMakeNoSpans() {
        ObjectNode d = F.objectNode().put("jobId", "job_old").put("workflow", "vm-restart").put("traceId", TRACE);
        bus.publish(new DomainEvent("evt_1", NOW.minus(Duration.ofMinutes(30)), "job.started", "job", "job_old", "ana", d));
        step("job_old", "restart", "provider", "running", 1, null);
        finished("job_old", "succeeded", null);
        started("job_rerun", "vm-restart", TRACE, null);
        bus.publish("job.step", "job", "job_rerun", "system", F.objectNode().put("jobId", "job_rerun").put("step", "restart")
                .put("type", "provider").put("status", "succeeded").put("replayed", true));
        finished("job_rerun", "succeeded", null);

        assertThat(tracing.named("step restart")).isEmpty();
        assertThat(tracing.named("job vm-restart")).hasSize(1);
        assertThat(attr(tracing.one("job vm-restart"), "job.id")).isEqualTo("job_rerun");
    }

    @Test
    void aProviderCallHangsUnderTheStepRunningOnItsThreadElseUnderTheJob() throws Exception {
        started("job_1", "vm-restart", TRACE, null);
        step("job_1", "restart", "provider", "running", 1, null);
        Observation here = jobs.parentOf("job_1");
        Observation elsewhere = CompletableFuture.supplyAsync(() -> jobs.parentOf("job_1")).get();
        step("job_1", "restart", "provider", "succeeded", 1, null);
        Observation after = jobs.parentOf("job_1");
        finished("job_1", "succeeded", null);

        assertThat(tracing.traces.spanId(here)).isEqualTo(tracing.one("step restart").getSpanId());
        assertThat(tracing.traces.spanId(elsewhere)).isEqualTo(tracing.one("job vm-restart").getSpanId());
        assertThat(tracing.traces.spanId(after)).isEqualTo(tracing.one("job vm-restart").getSpanId());
        assertThat(jobs.parentOf("job_1")).isNull();
        assertThat(jobs.parentOf(null)).isNull();
    }

    @Test
    void withTracingOffNothingIsKept() {
        JobTracing off = new JobTracing(bus, Traces.off(), Clock.fixed(NOW, ZoneOffset.UTC));
        off.subscribe();
        started("job_1", "vm-restart", TRACE, null);
        step("job_1", "restart", "provider", "running", 1, null);

        assertThat(off.openJobs()).isZero();
        assertThat(off.parentOf("job_1")).isNull();
    }
}
