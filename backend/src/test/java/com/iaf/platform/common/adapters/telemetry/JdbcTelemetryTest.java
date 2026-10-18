package com.iaf.platform.common.adapters.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.iaf.platform.common.adapters.jdbc.Jdbc;
import com.iaf.platform.common.adapters.jdbc.TestDatabase;
import com.iaf.platform.common.ports.TelemetryPort;
import com.iaf.platform.common.ports.TelemetryPort.CallSample;
import com.iaf.platform.config.PlatformProperties;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class JdbcTelemetryTest {

    abstract static class OnDatabase extends TelemetryContract {

        final List<JdbcTelemetry> made = new ArrayList<>();

        abstract TestDatabase db();

        JdbcTelemetry telemetry(Jdbc jdbc, MeterRegistry registry, Duration retention) {
            PlatformProperties props = new PlatformProperties();
            props.getPersistence().setTelemetryRetention(retention);
            JdbcTelemetry t = new JdbcTelemetry(jdbc, registry, CLOCK, props);
            made.add(t);
            return t;
        }

        @AfterEach
        void stopWriters() {
            made.forEach(JdbcTelemetry::close);
        }

        @Override
        TelemetryPort newStore(MeterRegistry registry) {
            return telemetry(db().jdbc(), registry, Duration.ofDays(30));
        }

        @Override
        TelemetryPort reopen() {
            return telemetry(new Jdbc(db().dataSource()), new SimpleMeterRegistry(), Duration.ofDays(30));
        }

        @Test
        void theBackgroundWriterStoresWhatWasRecorded() throws Exception {
            store().record(sample(NOW, "vsphere", true, 7, null));
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (count() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(count()).isEqualTo(1);
        }

        @Test
        void deletesSamplesOlderThanTheConfiguredRetention() {
            JdbcTelemetry telemetry = telemetry(db().jdbc(), new SimpleMeterRegistry(), Duration.ofDays(7));
            telemetry.record(sample(NOW.minus(Duration.ofDays(8)), "vsphere", true, 1, null));
            telemetry.record(sample(NOW.minus(Duration.ofDays(6)), "vsphere", true, 2, null));
            assertThat(telemetry.samples(null, null)).extracting(CallSample::durationMs).containsExactly(2L);
            assertThat(count()).isEqualTo(2);

            assertThat(telemetry.purge()).isEqualTo(1);
            assertThat(count()).isEqualTo(1);
        }

        @Test
        void writesManySamplesInBatches() {
            JdbcTelemetry telemetry = (JdbcTelemetry) store();
            for (int i = 0; i < JdbcTelemetry.BATCH * 2 + 7; i++) {
                telemetry.record(sample(NOW.minusMillis(i), "vsphere", true, i, null));
            }
            telemetry.flush();
            assertThat(count()).isEqualTo(JdbcTelemetry.BATCH * 2 + 7);
            assertThat(telemetry.samples("vsphere", null)).hasSize(JdbcTelemetry.BATCH * 2 + 7);
        }

        private long count() {
            return db().jdbc().first("SELECT COUNT(*) FROM telemetry_samples", (rs, i) -> rs.getLong(1)).orElse(0L);
        }
    }

    @Nested
    class OnH2 extends OnDatabase {
        @RegisterExtension
        final TestDatabase db = TestDatabase.h2();

        @Override
        TestDatabase db() {
            return db;
        }
    }

    @Nested
    @EnabledIfEnvironmentVariable(named = TestDatabase.POSTGRES_URL, matches = ".+")
    class OnPostgres extends OnDatabase {
        @RegisterExtension
        final TestDatabase db = TestDatabase.postgres();

        @Override
        TestDatabase db() {
            return db;
        }
    }
}
