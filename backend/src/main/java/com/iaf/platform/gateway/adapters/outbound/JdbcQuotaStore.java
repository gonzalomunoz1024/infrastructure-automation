package com.iaf.platform.gateway.adapters.outbound;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.iaf.platform.common.adapters.jdbc.Jdbc;
import com.iaf.platform.gateway.domain.TokenBucket;
import com.iaf.platform.gateway.ports.outbound.QuotaStore;

import lombok.extern.slf4j.Slf4j;

/**
 * The quotas of the {@code postgres} profile: one row per bucket in {@code quota_buckets}, shared
 * by every API replica. A take locks the row ({@code SELECT ... FOR UPDATE}), refills the bucket
 * to now, takes a token and writes it back, all in one transaction, so two replicas never hand
 * out the same token. A bucket idle for {@value #IDLE_HOURS} hours is full again, so a sweep
 * deletes such rows every ten minutes.
 */
@Slf4j
@Component
@Primary
@Profile("postgres")
public class JdbcQuotaStore implements QuotaStore {

    static final long IDLE_HOURS = 24;

    private record Row(double tokens, long lastNanos) {
    }

    private final Jdbc jdbc;
    private final Clock clock;

    public JdbcQuotaStore(Jdbc jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public Optional<Duration> take(String bucket, int perMinute, int burst, Instant now) {
        return jdbc.inTransaction(() -> {
            TokenBucket b = lock(bucket, perMinute, burst, now);
            Optional<Duration> wait = b.take(now);
            jdbc.update("UPDATE quota_buckets SET tokens = ?, last_ns = ? WHERE bucket = ?", b.tokens(), Jdbc.nanos(b.last()), bucket);
            return wait;
        });
    }

    /** The bucket's row, locked for this transaction; created full when there is none. */
    private TokenBucket lock(String bucket, int perMinute, int burst, Instant now) {
        Optional<Row> row = locked(bucket);
        if (row.isEmpty()) {
            if (jdbc.insertIfAbsent("INSERT INTO quota_buckets (bucket, tokens, last_ns) VALUES (?, ?, ?) ON CONFLICT DO NOTHING", bucket,
                    (double) burst, Jdbc.nanos(now))) {
                return new TokenBucket(perMinute, burst, now);
            }
            row = locked(bucket);   // another replica created it in between; it is committed now, so lock it
        }
        Row r = row.orElseThrow(() -> new IllegalStateException("quota bucket " + bucket + " came and went"));
        return TokenBucket.restore(perMinute, burst, r.tokens(), instant(r.lastNanos()));
    }

    private Optional<Row> locked(String bucket) {
        return jdbc.first("SELECT tokens, last_ns FROM quota_buckets WHERE bucket = ? FOR UPDATE", (rs, i) -> new Row(rs.getDouble(1), rs.getLong(2)),
                bucket);
    }

    /** Deletes the buckets idle long enough to be full again; returns how many. */
    public int sweep(Instant now) {
        return jdbc.update("DELETE FROM quota_buckets WHERE last_ns < ?", Jdbc.nanos(now.minus(Duration.ofHours(IDLE_HOURS))));
    }

    @Scheduled(fixedDelay = 10, initialDelay = 10, timeUnit = TimeUnit.MINUTES)
    void sweepOnSchedule() {
        try {
            int deleted = sweep(clock.instant());
            if (deleted > 0) {
                log.info("deleted {} quota buckets idle for {} hours", deleted, IDLE_HOURS);
            }
        } catch (RuntimeException e) {
            log.warn("idle quota buckets could not be deleted: {}", e.toString());
        }
    }

    /** The bucket's tokens as kept, without refilling (for tests and diagnosis). */
    public Optional<Double> tokensOf(String bucket) {
        return jdbc.first("SELECT tokens FROM quota_buckets WHERE bucket = ?", (rs, i) -> rs.getDouble(1), bucket);
    }

    private static Instant instant(long nanos) {
        return Instant.ofEpochSecond(Math.floorDiv(nanos, 1_000_000_000L), Math.floorMod(nanos, 1_000_000_000L));
    }
}
