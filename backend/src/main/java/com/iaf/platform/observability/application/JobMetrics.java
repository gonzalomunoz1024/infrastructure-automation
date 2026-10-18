package com.iaf.platform.observability.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.common.events.DomainEvent;
import com.iaf.platform.common.events.EventBus;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.annotation.PostConstruct;

/**
 * Per-workflow metrics from the job events, for {@code /actuator/prometheus}:
 * <ul>
 * <li>{@value #STARTED} (workflow, version, channel, kind) and {@value #FINISHED} (the same and
 * the status: succeeded, failed, cancelled), counters;</li>
 * <li>{@value #DURATION} (workflow, version), a timer with a percentile histogram, of single runs
 * (a batch's rows each count; the batch around them does not);</li>
 * <li>{@value #STEP_FAILURES} (workflow, class), a counter of failed step attempts by error
 * class.</li>
 * </ul>
 * Only {@link LiveEvents live} events count, so the history the demo seeds counts for nothing. A
 * job this process did not see start finishes with {@code unknown} channel and kind.
 */
@Component
public class JobMetrics {

    public static final String STARTED = "workflow.jobs.started";
    public static final String FINISHED = "workflow.jobs.finished";
    public static final String DURATION = "workflow.jobs.duration";
    public static final String STEP_FAILURES = "workflow.steps.failed";
    static final String UNKNOWN = "unknown";
    static final int MAX_RUNNING = 10_000;

    private final EventBus events;
    private final MeterRegistry registry;
    private final Clock clock;
    private final Map<String, Running> running = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Running> eldest) {
            return size() > MAX_RUNNING;
        }
    };

    /** What the finish of a job started here needs to know about its start. */
    private record Running(String workflow, String version, String channel, String kind, Instant startedAt) {
    }

    @Autowired
    public JobMetrics(EventBus events, ObjectProvider<MeterRegistry> registry, Clock clock) {
        this(events, registry.getIfAvailable(SimpleMeterRegistry::new), clock);
    }

    public JobMetrics(EventBus events, MeterRegistry registry, Clock clock) {
        this.events = events;
        this.registry = registry;
        this.clock = clock;
    }

    @PostConstruct
    public void subscribe() {
        events.register(this::on);
    }

    void on(DomainEvent event) {
        if (event == null || event.type() == null || !event.type().startsWith("job.") || event.data() == null) {
            return;
        }
        JsonNode d = event.data();
        String jobId = text(d, "jobId", event.aggregateId());
        if (jobId == null) {
            return;
        }
        boolean live = LiveEvents.live(event.at(), clock);
        switch (event.type()) {
            case "job.started" -> {
                if (live) {
                    started(jobId, d, event.at());
                }
            }
            case "job.step" -> {
                if (live && "failed".equals(text(d, "status", null))) {
                    stepFailed(jobId, text(d.path("error"), "class", "error"));
                }
            }
            case "job.finished" -> finished(jobId, live, text(d, "workflow", null), text(d, "version", null), text(d, "status", UNKNOWN),
                    d.hasNonNull("durationMs") ? d.get("durationMs").asLong() : null, event.at());
            case "job.cancelled" -> finished(jobId, live, null, null, "cancelled", null, event.at());
            default -> {
            }
        }
    }

    private void started(String jobId, JsonNode d, Instant at) {
        Running r = new Running(text(d, "workflow", UNKNOWN), text(d, "version", UNKNOWN), text(d, "channel", UNKNOWN), text(d, "kind", "single"),
                at == null ? clock.instant() : at);
        synchronized (running) {
            running.put(jobId, r);
        }
        Counter.builder(STARTED).description("Jobs started")
                .tags("workflow", r.workflow(), "version", r.version(), "channel", r.channel(), "kind", r.kind())
                .register(registry).increment();
    }

    private void finished(String jobId, boolean live, String workflow, String version, String status, Long durationMs, Instant at) {
        Running r;
        synchronized (running) {
            r = running.remove(jobId);
        }
        if (!live) {
            return;
        }
        String w = workflow != null ? workflow : r == null ? UNKNOWN : r.workflow();
        String v = version != null ? version : r == null ? UNKNOWN : r.version();
        String kind = r == null ? UNKNOWN : r.kind();
        Counter.builder(FINISHED).description("Jobs finished, by status")
                .tags("workflow", w, "version", v, "channel", r == null ? UNKNOWN : r.channel(), "kind", kind, "status", status)
                .register(registry).increment();
        Long ms = durationMs != null ? durationMs : r == null || at == null ? null : Duration.between(r.startedAt(), at).toMillis();
        if (ms != null && ms >= 0 && !"batch".equals(kind)) {
            Timer.builder(DURATION).description("How long a run of the workflow takes, from start to end")
                    .tags("workflow", w, "version", v)
                    .publishPercentileHistogram()
                    .minimumExpectedValue(Duration.ofMillis(100))
                    .maximumExpectedValue(Duration.ofHours(24))
                    .register(registry).record(ms, TimeUnit.MILLISECONDS);
        }
    }

    private void stepFailed(String jobId, String errorClass) {
        Running r;
        synchronized (running) {
            r = running.get(jobId);
        }
        Counter.builder(STEP_FAILURES).description("Failed step attempts, by error class")
                .tags("workflow", r == null ? UNKNOWN : r.workflow(), "class", errorClass)
                .register(registry).increment();
    }

    private static String text(JsonNode d, String field, String fallback) {
        JsonNode v = d == null ? null : d.get(field);
        return v == null || v.isNull() || v.asText().isBlank() ? fallback : v.asText();
    }
}
