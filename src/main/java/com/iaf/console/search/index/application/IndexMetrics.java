package com.iaf.console.search.index.application;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/** The service's own meters: events by type and outcome, queries by kind and outcome, rebuilds, lag. */
public final class IndexMetrics {

    private final MeterRegistry registry;
    private final AtomicLong lagSeconds = new AtomicLong();
    private final AtomicLong documents = new AtomicLong();

    public IndexMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("console_search_lag_seconds", lagSeconds, AtomicLong::get).description("How far the newest applied event is behind now")
                .register(registry);
        Gauge.builder("console_search_documents", documents, AtomicLong::get).description("Documents in the index at the last count").register(registry);
    }

    public void event(String type, String outcome) {
        Counter.builder("console_search_events_total").description("Events handled, by type and outcome").tag("type", type).tag("outcome", outcome)
                .register(registry).increment();
    }

    public void query(String kind, String outcome) {
        Counter.builder("console_search_queries_total").description("Queries by kind and outcome").tag("kind", kind).tag("outcome", outcome)
                .register(registry).increment();
    }

    public void rebuild(String outcome) {
        Counter.builder("console_search_rebuilds_total").description("Rebuild runs by outcome").tag("outcome", outcome).register(registry).increment();
    }

    public void refetched() {
        Counter.builder("console_search_refetches_total").description("Resources read again from the state service").register(registry).increment();
    }

    public void lag(double seconds) {
        lagSeconds.set(Math.round(seconds));
    }

    public void documents(long count) {
        documents.set(count);
    }

    public Timer.Sample start() {
        return Timer.start(registry);
    }

    public void stop(Timer.Sample sample, String what) {
        sample.stop(Timer.builder("console_search_duration").description("Time spent in queries and event handling").tag("what", what)
                .serviceLevelObjectives(LATENCY_BUCKETS)
                .register(registry));
    }

    /**
     * The histogram's buckets, closest around the plan's targets (a list's counts under 300 ms, a
     * search under 500 ms at p95), so the latency dashboard reads its p95 from them; ten buckets per
     * kind of query, where a full percentile histogram would publish about seventy.
     */
    static final Duration[] LATENCY_BUCKETS = {Duration.ofMillis(10), Duration.ofMillis(25), Duration.ofMillis(50), Duration.ofMillis(100),
            Duration.ofMillis(200), Duration.ofMillis(300), Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(5)};
}
