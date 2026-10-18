package com.iaf.platform.observability.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.events.DomainEvent;
import com.iaf.platform.common.events.EventBus;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

class JobMetricsTest {

    static final Instant T0 = Instant.parse("2026-10-18T10:00:00Z");
    static final JsonNodeFactory F = JsonNodeFactory.instance;

    /** A clock the test moves. */
    static final class MovingClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(T0);

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        void advance(Duration d) {
            now.updateAndGet(i -> i.plus(d));
        }
    }

    MovingClock clock = new MovingClock();
    EventBus bus = new EventBus(clock);
    MeterRegistry meters = new SimpleMeterRegistry();

    @BeforeEach
    void subscribe() {
        new JobMetrics(bus, meters, clock).subscribe();
    }

    void started(String jobId, String workflow, String version, String channel, String kind) {
        bus.publish("job.started", "job", jobId, "ana", F.objectNode().put("jobId", jobId).put("workflow", workflow).put("version", version)
                .put("channel", channel).put("kind", kind));
    }

    void finished(String jobId, String workflow, String version, String status, Long durationMs) {
        ObjectNode d = F.objectNode().put("jobId", jobId).put("workflow", workflow).put("version", version).put("status", status);
        if (durationMs != null) {
            d.put("durationMs", durationMs);
        }
        bus.publish("job.finished", "job", jobId, "system", d);
    }

    void stepFailed(String jobId, String errorClass) {
        bus.publish("job.step", "job", jobId, "system", F.objectNode().put("jobId", jobId).put("step", "restart").put("type", "provider")
                .put("status", "failed").put("attempt", 1).set("error", F.objectNode().put("class", errorClass).put("message", "m")));
    }

    /** The sum over every counter of {@code name} with these tags (and any others). */
    double count(String name, String... tags) {
        return meters.find(name).tags(tags).counters().stream().mapToDouble(c -> c.count()).sum();
    }

    @Test
    void jobsAreCountedByWorkflowVersionChannelAndStatusAndTimedByWorkflowAndVersion() {
        started("job_1", "vm-restart", "1.2.0", "rest", "single");
        started("job_2", "vm-restart", "1.2.0", "mcp", "single");
        started("job_3", "vm-restart", "1.3.0", "rest", "single");
        finished("job_1", "vm-restart", "1.2.0", "succeeded", 4_000L);
        finished("job_2", "vm-restart", "1.2.0", "failed", 2_000L);

        assertThat(count(JobMetrics.STARTED, "workflow", "vm-restart", "version", "1.2.0", "channel", "rest", "kind", "single")).isEqualTo(1);
        assertThat(count(JobMetrics.STARTED, "workflow", "vm-restart", "version", "1.2.0")).isEqualTo(2);
        assertThat(count(JobMetrics.STARTED, "workflow", "vm-restart")).isEqualTo(3);
        assertThat(count(JobMetrics.FINISHED, "workflow", "vm-restart", "version", "1.2.0", "channel", "rest", "status", "succeeded")).isEqualTo(1);
        assertThat(count(JobMetrics.FINISHED, "workflow", "vm-restart", "channel", "mcp", "status", "failed")).isEqualTo(1);
        Timer timer = meters.find(JobMetrics.DURATION).tags("workflow", "vm-restart", "version", "1.2.0").timer();
        assertThat(timer.count()).isEqualTo(2);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(6_000);
        assertThat(timer.getId().getTags()).extracting(t -> t.getKey()).containsExactlyInAnyOrder("workflow", "version");
    }

    @Test
    void stepFailuresAreCountedByErrorClass() {
        started("job_1", "vm-restart", "1.2.0", "rest", "single");
        stepFailed("job_1", "timeout");
        stepFailed("job_1", "timeout");
        stepFailed("job_1", "not_found");
        stepFailed("job_unseen", "timeout");

        assertThat(count(JobMetrics.STEP_FAILURES, "workflow", "vm-restart", "class", "timeout")).isEqualTo(2);
        assertThat(count(JobMetrics.STEP_FAILURES, "workflow", "vm-restart", "class", "not_found")).isEqualTo(1);
        assertThat(count(JobMetrics.STEP_FAILURES, "workflow", JobMetrics.UNKNOWN, "class", "timeout")).isEqualTo(1);
    }

    @Test
    void aCancelledJobFinishesCancelledAndIsTimedFromItsStart() {
        started("job_1", "vm-restart", "1.2.0", "rest", "single");
        clock.advance(Duration.ofSeconds(90));
        bus.publish("job.cancelled", "job", "job_1", "ana", F.objectNode().put("jobId", "job_1").put("by", "ana"));

        assertThat(count(JobMetrics.FINISHED, "workflow", "vm-restart", "version", "1.2.0", "channel", "rest", "status", "cancelled")).isEqualTo(1);
        assertThat(meters.find(JobMetrics.DURATION).tags("workflow", "vm-restart").timer().totalTime(TimeUnit.SECONDS)).isEqualTo(90);
    }

    @Test
    void aBatchCountsAsAJobButOnlyItsRowsAreTimed() {
        started("job_b", "vm-restart", "1.2.0", "rest", "batch");
        started("job_r1", "vm-restart", "1.2.0", "batch", "single");
        finished("job_r1", "vm-restart", "1.2.0", "succeeded", 1_000L);
        finished("job_b", "vm-restart", "1.2.0", "succeeded", 60_000L);

        assertThat(count(JobMetrics.FINISHED, "workflow", "vm-restart", "kind", "batch")).isEqualTo(1);
        assertThat(count(JobMetrics.FINISHED, "workflow", "vm-restart", "channel", "batch", "kind", "single")).isEqualTo(1);
        Timer timer = meters.find(JobMetrics.DURATION).tags("workflow", "vm-restart").timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(1_000);
    }

    @Test
    void historyCountsForNothingAndAJobNotSeenStartingFinishesUnknown() {
        ObjectNode d = F.objectNode().put("jobId", "job_old").put("workflow", "vm-restart").put("version", "1.0.0").put("channel", "rest");
        bus.publish(new DomainEvent("evt_1", T0.minus(Duration.ofDays(3)), "job.started", "job", "job_old", "ana", d));
        bus.publish(new DomainEvent("evt_2", T0.minus(Duration.ofDays(3)), "job.finished", "job", "job_old", "system",
                d.deepCopy().put("status", "succeeded").put("durationMs", 5)));
        finished("job_live", "vm-restart", "1.2.0", "failed", 3_000L);

        assertThat(meters.find(JobMetrics.STARTED).counters()).isEmpty();
        assertThat(count(JobMetrics.FINISHED, "version", "1.0.0")).isZero();
        assertThat(count(JobMetrics.FINISHED, "workflow", "vm-restart", "version", "1.2.0", "channel", JobMetrics.UNKNOWN, "kind", JobMetrics.UNKNOWN,
                "status", "failed")).isEqualTo(1);
    }

    @Test
    void prometheusExposesTheCountersAndTheDurationHistogram() {
        PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        EventBus own = new EventBus(clock);
        new JobMetrics(own, prometheus, clock).subscribe();
        own.publish("job.started", "job", "job_1", "ana", F.objectNode().put("jobId", "job_1").put("workflow", "vm-restart").put("version", "1.2.0")
                .put("channel", "rest").put("kind", "single"));
        own.publish("job.finished", "job", "job_1", "system", F.objectNode().put("jobId", "job_1").put("workflow", "vm-restart")
                .put("version", "1.2.0").put("status", "succeeded").put("durationMs", 1500));

        String scrape = prometheus.scrape();
        assertThat(scrape).contains("workflow_jobs_started_total{channel=\"rest\",kind=\"single\",version=\"1.2.0\",workflow=\"vm-restart\"} 1.0");
        assertThat(scrape).contains("workflow_jobs_finished_total{channel=\"rest\",kind=\"single\",status=\"succeeded\",version=\"1.2.0\",workflow=\"vm-restart\"} 1.0");
        assertThat(scrape).contains("workflow_jobs_duration_seconds_bucket{version=\"1.2.0\",workflow=\"vm-restart\",le=");
        assertThat(scrape).contains("workflow_jobs_duration_seconds_count{version=\"1.2.0\",workflow=\"vm-restart\"} 1");
    }
}
