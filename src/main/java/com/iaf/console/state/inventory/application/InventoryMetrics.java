package com.iaf.console.state.inventory.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/** The service's own meters: commands by type and outcome, events appended, collisions refused, reaper outcomes. */
public final class InventoryMetrics {

    private final MeterRegistry registry;

    public InventoryMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void command(String type, String outcome) {
        Counter.builder("console_state_commands_total").description("Commands by type and outcome").tag("type", type).tag("outcome", outcome)
                .register(registry).increment();
    }

    public void event(String type) {
        Counter.builder("console_state_events_total").description("Resource events appended to the log").tag("type", type).register(registry)
                .increment();
    }

    public void collision() {
        Counter.builder("console_state_collisions_total").description("Registry ids refused because another live resource holds them")
                .register(registry).increment();
    }

    public void reaped(String outcome) {
        Counter.builder("console_state_reaper_total").description("Pending resources the reaper settled, by outcome").tag("outcome", outcome)
                .register(registry).increment();
    }

    public void ingested(String type, String outcome) {
        Counter.builder("console_state_ingested_total").description("Platform events handled, by type and outcome").tag("type", type)
                .tag("outcome", outcome).register(registry).increment();
    }

    public Timer.Sample start() {
        return Timer.start(registry);
    }

    public void stop(Timer.Sample sample, String what) {
        sample.stop(Timer.builder("console_state_duration").description("Time spent in commands and queries").tag("what", what).register(registry));
    }
}
