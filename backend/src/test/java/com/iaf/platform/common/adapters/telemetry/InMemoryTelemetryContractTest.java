package com.iaf.platform.common.adapters.telemetry;

import com.iaf.platform.common.ports.TelemetryPort;

import io.micrometer.core.instrument.MeterRegistry;

class InMemoryTelemetryContractTest extends TelemetryContract {

    @Override
    TelemetryPort newStore(MeterRegistry registry) {
        return new InMemoryTelemetry(registry, CLOCK);
    }

    @Override
    TelemetryPort reopen() {
        return null;
    }
}
