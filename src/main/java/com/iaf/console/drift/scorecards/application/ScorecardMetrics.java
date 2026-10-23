package com.iaf.console.drift.scorecards.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/** The scorecards' meters: evaluations made, skipped and changed, the engine's failures, remediations started. */
public final class ScorecardMetrics {

    private final MeterRegistry registry;

    public ScorecardMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void evaluated(String outcome) {
        count("console_scorecards_evaluations_total", "outcome", outcome).increment();
    }

    public void engineError(String engine) {
        count("console_scorecards_engine_errors_total", "engine", engine).increment();
    }

    public void remediation(String kind, String outcome) {
        count("console_scorecards_remediations_total", "kind", kind, "outcome", outcome).increment();
    }

    public void snapshot(String outcome) {
        count("console_scorecards_snapshots_total", "outcome", outcome).increment();
    }

    private Counter count(String name, String... tags) {
        return Counter.builder(name).tags(tags).register(registry);
    }
}
