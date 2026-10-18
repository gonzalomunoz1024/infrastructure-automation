package com.iaf.platform.common.adapters.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.iaf.platform.common.ports.TelemetryPort;
import com.iaf.platform.common.ports.TelemetryPort.CallSample;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** What every {@link TelemetryPort} does, in memory and in a database. */
abstract class TelemetryContract {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00.123456789Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    /** An empty store with the default retention (30 days). */
    abstract TelemetryPort newStore(MeterRegistry registry);

    /** A new store over the same storage as the last one made; null when the storage does not outlive the store. */
    abstract TelemetryPort reopen();

    static CallSample sample(Instant at, String provider, boolean ok, long ms, String errorClass) {
        return new CallSample(at, provider, "vm.get", "dev", ok, ms, errorClass, "job_1", "vm-restart", "1.2.0");
    }

    TelemetryPort store() {
        return newStore(new SimpleMeterRegistry());
    }

    @Test
    void samplesComeBackOldestFirstFilteredByProviderAndTime() {
        TelemetryPort telemetry = store();
        telemetry.record(sample(NOW.minusSeconds(10), "vsphere", true, 100, null));
        telemetry.record(sample(NOW.minusSeconds(30), "vsphere", false, 2000, "unavailable"));
        telemetry.record(sample(NOW.minusSeconds(20), "backup", true, 50, null));
        telemetry.record(sample(NOW.minus(Duration.ofDays(2)), "vsphere", true, 10, null));

        List<CallSample> all = telemetry.samples(null, NOW.minus(Duration.ofHours(24)));
        assertThat(all).extracting(CallSample::at).containsExactly(NOW.minusSeconds(30), NOW.minusSeconds(20), NOW.minusSeconds(10));
        assertThat(all.get(0)).isEqualTo(sample(NOW.minusSeconds(30), "vsphere", false, 2000, "unavailable"));
        assertThat(telemetry.samples("vsphere", NOW.minus(Duration.ofHours(24)))).hasSize(2);
        assertThat(telemetry.samples("vsphere", NOW.minus(Duration.ofDays(3)))).hasSize(3);
        assertThat(telemetry.samples("nope", NOW.minus(Duration.ofDays(3)))).isEmpty();
    }

    @Test
    void sinceIsInclusiveToTheNanosecond() {
        TelemetryPort telemetry = store();
        telemetry.record(sample(NOW.minusNanos(1), "vsphere", true, 1, null));
        telemetry.record(sample(NOW, "vsphere", true, 2, null));
        assertThat(telemetry.samples("vsphere", NOW)).extracting(CallSample::durationMs).containsExactly(2L);
        assertThat(telemetry.samples("vsphere", NOW.minusNanos(1))).extracting(CallSample::durationMs).containsExactly(1L, 2L);
    }

    @Test
    void samplesAtTheSameTimeKeepTheirOrder() {
        TelemetryPort telemetry = store();
        for (int i = 0; i < 5; i++) {
            telemetry.record(sample(NOW, "vsphere", true, i, null));
        }
        assertThat(telemetry.samples(null, null)).extracting(CallSample::durationMs).containsExactly(0L, 1L, 2L, 3L, 4L);
    }

    @Test
    void metersAreRegisteredForPrometheus() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TelemetryPort telemetry = newStore(registry);
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
        TelemetryPort telemetry = store();
        telemetry.record(new CallSample(null, "vsphere", "vm.get", "dev", true, 5, null, null, null, null));
        telemetry.record(null);
        assertThat(telemetry.samples("vsphere", NOW.minusSeconds(1)))
                .containsExactly(new CallSample(NOW, "vsphere", "vm.get", "dev", true, 5, null, null, null, null));
    }

    @Test
    void samplesOlderThanTheRetentionAreNeverServed() {
        TelemetryPort telemetry = store();
        telemetry.record(sample(NOW.minus(Duration.ofDays(31)), "vsphere", true, 1, null));
        telemetry.record(sample(NOW.minus(Duration.ofDays(29)), "vsphere", true, 2, null));
        assertThat(telemetry.samples(null, NOW.minus(Duration.ofDays(40)))).extracting(CallSample::durationMs).containsExactly(2L);
        assertThat(telemetry.samples(null, null)).extracting(CallSample::durationMs).containsExactly(2L);
    }

    @Test
    void keepsSamplesAcrossARestart() {
        TelemetryPort telemetry = store();
        CallSample s = sample(NOW.minusSeconds(5), "vsphere", false, 2000, "unavailable");
        telemetry.record(s);
        telemetry.samples(null, null);
        TelemetryPort after = reopen();
        assumeTrue(after != null, "this store does not outlive the process");
        assertThat(after.samples("vsphere", null)).containsExactly(s);
    }
}
