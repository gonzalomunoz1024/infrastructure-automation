package com.iaf.platform.common.adapters.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.iaf.platform.common.ports.TelemetryPort.CallSample;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class InMemoryTelemetryTest {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    static CallSample sample(Instant at, String provider, boolean ok, long ms, String errorClass) {
        return new CallSample(at, provider, "vm.get", "dev", ok, ms, errorClass, "job_1", "vm-restart", "1.2.0");
    }

    @Test
    void samplesComeBackOldestFirstFilteredByProviderAndTime() {
        InMemoryTelemetry telemetry = new InMemoryTelemetry(new SimpleMeterRegistry(), CLOCK);
        telemetry.record(sample(NOW.minusSeconds(10), "vsphere", true, 100, null));
        telemetry.record(sample(NOW.minusSeconds(30), "vsphere", false, 2000, "unavailable"));
        telemetry.record(sample(NOW.minusSeconds(20), "backup", true, 50, null));
        telemetry.record(sample(NOW.minus(Duration.ofDays(2)), "vsphere", true, 10, null));

        List<CallSample> all = telemetry.samples(null, NOW.minus(Duration.ofHours(24)));
        assertThat(all).extracting(CallSample::at).containsExactly(NOW.minusSeconds(30), NOW.minusSeconds(20), NOW.minusSeconds(10));
        assertThat(telemetry.samples("vsphere", NOW.minus(Duration.ofHours(24)))).hasSize(2);
        assertThat(telemetry.samples("vsphere", NOW.minus(Duration.ofDays(3)))).hasSize(3);
        assertThat(telemetry.samples("nope", NOW.minus(Duration.ofDays(3)))).isEmpty();
        assertThat(telemetry.size()).isEqualTo(4);
    }

    @Test
    void metersAreRegisteredForPrometheus() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InMemoryTelemetry telemetry = new InMemoryTelemetry(registry, CLOCK);
        telemetry.record(sample(NOW, "vsphere", true, 100, null));
        telemetry.record(sample(NOW, "vsphere", true, 300, null));
        telemetry.record(sample(NOW, "vsphere", false, 900, "unavailable"));
        telemetry.record(sample(NOW, "vsphere", false, 900, null));

        assertThat(registry.find("provider.call").tag("provider", "vsphere").tag("action", "vm.get").tag("outcome", "ok").timer().count()).isEqualTo(2);
        assertThat(registry.find("provider.call").tag("outcome", "error").timer().count()).isEqualTo(2);
        assertThat(registry.find("provider.errors").tag("class", "unavailable").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("provider.errors").tag("class", "unknown").counter().count()).isEqualTo(1.0);
    }

    @Test
    void aSampleWithoutATimeIsStampedNow() {
        InMemoryTelemetry telemetry = new InMemoryTelemetry(new SimpleMeterRegistry(), CLOCK);
        telemetry.record(new CallSample(null, "vsphere", "vm.get", "dev", true, 5, null, null, null, null));
        telemetry.record(null);
        assertThat(telemetry.samples("vsphere", NOW.minusSeconds(1))).extracting(CallSample::at).containsExactly(NOW);
    }

    @Test
    void oldSamplesAreNeverServedAndTheStoreIsBounded() {
        InMemoryTelemetry telemetry = new InMemoryTelemetry(new SimpleMeterRegistry(), CLOCK);
        telemetry.record(sample(NOW.minus(Duration.ofDays(31)), "vsphere", true, 1, null));
        assertThat(telemetry.samples(null, NOW.minus(Duration.ofDays(40)))).isEmpty();
        for (int i = 0; i < InMemoryTelemetry.MAX_SAMPLES + 50; i++) {
            telemetry.record(sample(NOW.minusSeconds(i % 1000), "vsphere", true, 1, null));
        }
        assertThat(telemetry.size()).isEqualTo(InMemoryTelemetry.MAX_SAMPLES);
        assertThat(telemetry.samples("vsphere", NOW.minusSeconds(2000))).hasSize(InMemoryTelemetry.MAX_SAMPLES);
    }
}
