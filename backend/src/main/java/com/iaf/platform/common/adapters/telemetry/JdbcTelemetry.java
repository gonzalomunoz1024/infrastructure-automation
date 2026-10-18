package com.iaf.platform.common.adapters.telemetry;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.platform.common.adapters.jdbc.Jdbc;
import com.iaf.platform.common.ports.TelemetryPort;
import com.iaf.platform.config.PlatformProperties;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * The provider-call samples of the {@code postgres} profile, in {@code telemetry_samples}, kept
 * for {@code platform.persistence.telemetry-retention} (older rows are deleted every hour and are
 * never returned). {@link #record} never waits for the database: a sample is queued and a
 * background writer inserts the queue in batches, so a call recorded on a Netty or timer thread
 * costs nothing there; a read writes what is queued first, so it sees every recorded call. The
 * Micrometer meters are the in-memory adapter's ({@code provider.call}, {@code provider.errors}).
 */
@Slf4j
@Component
@Primary
@Profile("postgres")
public class JdbcTelemetry implements TelemetryPort {

    static final int BATCH = 500;
    static final long FLUSH_EVERY_MS = 250;

    private static final String INSERT = "INSERT INTO telemetry_samples (at, at_ns, provider, action, environment, ok, duration_ms, error_class, job_id, workflow, version,"
            + " provider_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final Jdbc jdbc;
    private final MeterRegistry registry;
    private final Clock clock;
    private final Duration retention;
    private final ConcurrentLinkedQueue<CallSample> queued = new ConcurrentLinkedQueue<>();
    private final Object writing = new Object();
    private final ScheduledExecutorService writer;

    public JdbcTelemetry(Jdbc jdbc, MeterRegistry registry, Clock clock, PlatformProperties props) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.clock = clock;
        Duration configured = props.getPersistence().getTelemetryRetention();
        this.retention = configured == null || configured.isNegative() || configured.isZero() ? Duration.ofDays(InMemoryTelemetry.RETENTION_DAYS) : configured;
        this.writer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "telemetry-writer");
            t.setDaemon(true);
            return t;
        });
        writer.scheduleWithFixedDelay(this::flushQuietly, FLUSH_EVERY_MS, FLUSH_EVERY_MS, TimeUnit.MILLISECONDS);
        writer.scheduleWithFixedDelay(this::purgeQuietly, 1, 60, TimeUnit.MINUTES);
    }

    @Override
    public void record(CallSample sample) {
        if (sample == null) {
            return;
        }
        CallSample s = sample.at() == null ? new CallSample(clock.instant(), sample.provider(), sample.action(), sample.environment(), sample.ok(),
                sample.durationMs(), sample.errorClass(), sample.jobId(), sample.workflow(), sample.version(), sample.providerVersion()) : sample;
        queued.add(s);
        String provider = tag(s.provider());
        String action = tag(s.action());
        Timer.builder(InMemoryTelemetry.CALL_TIMER)
                .description("Provider call duration")
                .tags("provider", provider, "action", action, "outcome", s.ok() ? "ok" : "error")
                .register(registry)
                .record(Math.max(0, s.durationMs()), TimeUnit.MILLISECONDS);
        if (!s.ok()) {
            Counter.builder(InMemoryTelemetry.ERROR_COUNTER)
                    .description("Provider call errors by class")
                    .tags("provider", provider, "action", action, "class", tag(s.errorClass()))
                    .register(registry)
                    .increment();
        }
    }

    @Override
    public List<CallSample> samples(String provider, Instant since) {
        flush();
        Instant floor = clock.instant().minus(retention);
        Instant from = since == null || since.isBefore(floor) ? floor : since;
        String sql = "SELECT at_ns, provider, action, environment, ok, duration_ms, error_class, job_id, workflow, version, provider_version"
                + " FROM telemetry_samples WHERE at_ns >= ?" + (provider == null ? "" : " AND provider = ?") + " ORDER BY at_ns, id";
        Object[] args = provider == null ? new Object[] {Jdbc.nanos(from)} : new Object[] {Jdbc.nanos(from), provider};
        return jdbc.query(sql, (rs, i) -> new CallSample(instant(rs.getLong(1)), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5),
                rs.getLong(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11)), args);
    }

    /** Writes every queued sample now. */
    public void flush() {
        synchronized (writing) {
            List<Object[]> rows = new ArrayList<>();
            for (CallSample s = queued.poll(); s != null; s = queued.poll()) {
                rows.add(new Object[] {Jdbc.time(s.at()), Jdbc.nanos(s.at()), s.provider(), s.action(), s.environment(), s.ok(), s.durationMs(), s.errorClass(),
                        s.jobId(), s.workflow(), s.version(), s.providerVersion()});
                if (rows.size() == BATCH) {
                    jdbc.batch(INSERT, rows);
                    rows = new ArrayList<>();
                }
            }
            jdbc.batch(INSERT, rows);
        }
    }

    /** Deletes the samples older than the retention; returns how many. */
    public int purge() {
        return jdbc.update("DELETE FROM telemetry_samples WHERE at_ns < ?", Jdbc.nanos(clock.instant().minus(retention)));
    }

    @PreDestroy
    public void close() {
        writer.shutdown();
        try {
            writer.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        flushQuietly();
    }

    private void flushQuietly() {
        try {
            flush();
        } catch (RuntimeException e) {
            log.warn("provider-call samples could not be written: {}", e.toString());
        }
    }

    private void purgeQuietly() {
        try {
            int deleted = purge();
            if (deleted > 0) {
                log.info("deleted {} provider-call samples older than {}", deleted, retention);
            }
        } catch (RuntimeException e) {
            log.warn("old provider-call samples could not be deleted: {}", e.toString());
        }
    }

    private static Instant instant(long nanos) {
        return Instant.ofEpochSecond(Math.floorDiv(nanos, 1_000_000_000L), Math.floorMod(nanos, 1_000_000_000L));
    }

    private static String tag(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
