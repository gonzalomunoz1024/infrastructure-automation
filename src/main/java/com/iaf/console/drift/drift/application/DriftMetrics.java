package com.iaf.console.drift.drift.application;

import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/** The service's own meters: reads, findings, deferrals, platform refusals, the watch list's size. */
public final class DriftMetrics {

    private final MeterRegistry registry;
    private final AtomicLong watched = new AtomicLong();

    public DriftMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("console_drift_watched_resources", watched, AtomicLong::get).description("resources on the watch list").register(registry);
    }

    public void checked(String type, int n) {
        count("console_drift_checked_total", "type", type).increment(n);
    }

    public void finding(String action, String severity) {
        count("console_drift_findings_total", "action", action, "severity", severity).increment();
    }

    public void deferred(String reason, int n) {
        count("console_drift_deferred_total", "reason", reason).increment(n);
    }

    public void error(String where) {
        count("console_drift_errors_total", "where", where).increment();
    }

    public void stateCall(String command, String outcome) {
        count("console_drift_state_calls_total", "command", command, "outcome", outcome).increment();
    }

    public void watched(long n) {
        watched.set(n);
    }

    private Counter count(String name, String... tags) {
        return Counter.builder(name).tags(tags).register(registry);
    }
}
