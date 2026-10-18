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

import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.platform.common.adapters.mongo.Mongo;
import com.iaf.platform.common.ports.TelemetryPort;
import com.iaf.platform.config.PlatformProperties;
import com.mongodb.MongoCommandException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * The provider-call samples of the {@code mongo} profile, in {@code telemetry_samples}, kept for
 * {@code platform.persistence.telemetry-retention}: older samples are never returned, every
 * instance deletes them every hour, and MongoDB deletes them too, through a TTL index whose
 * lifetime is the retention (a changed retention takes effect on the next start).
 * {@link #record} never waits for the database: a sample is queued and a background writer
 * inserts the queue in batches, so a call recorded on a Netty or timer thread costs nothing there;
 * a read writes what is queued first, so it sees every recorded call. The Micrometer meters are
 * the in-memory adapter's ({@code provider.call}, {@code provider.errors}).
 *
 * <p>One document per call: the sample under {@code doc}, its time as epoch nanoseconds
 * ({@code atNs}, exact, for the range and the order) and as a date ({@code at}, which the TTL
 * index reads), and its {@code provider}. The {@code _id} is an ObjectId made as the queue is
 * written, in the order the calls were recorded, so samples of the same instant keep that order.
 */
@Slf4j
@Component
@Primary
@Profile("mongo")
public final class MongoTelemetry implements TelemetryPort {

    static final String COLLECTION = "telemetry_samples";
    static final int BATCH = 500;
    static final long FLUSH_EVERY_MS = 250;

    private static final Bson OLDEST_FIRST = Sorts.ascending("atNs", "_id");
    /** What createIndex answers when an index on the same keys exists with other options. */
    private static final int INDEX_OPTIONS_CONFLICT = 85;

    private final Mongo mongo;
    private final MeterRegistry registry;
    private final Clock clock;
    private final Duration retention;
    private final ConcurrentLinkedQueue<CallSample> queued = new ConcurrentLinkedQueue<>();
    private final Object writing = new Object();
    private final ScheduledExecutorService writer;

    @Autowired
    public MongoTelemetry(Mongo mongo, MongoClient client, MeterRegistry registry, Clock clock, PlatformProperties props) {
        this(mongo, registry, clock, props.getPersistence().getTelemetryRetention(), client.getDatabase(mongo.name()));
    }

    /**
     * A store whose expired samples only the hourly purge deletes, for a store on a clock of its
     * own (such as a test's fixed clock, behind the server's): MongoDB's TTL monitor judges by the
     * server's clock and would delete samples this store still serves.
     *
     * @param retention how long samples are kept; null, zero or negative keeps them
     *        {@value InMemoryTelemetry#RETENTION_DAYS} days
     */
    public MongoTelemetry(Mongo mongo, MeterRegistry registry, Clock clock, Duration retention) {
        this(mongo, registry, clock, retention, null);
    }

    /** @param database the database of {@code mongo}, where MongoDB deletes the expired samples too ({@link #expireIn}); null for none */
    MongoTelemetry(Mongo mongo, MeterRegistry registry, Clock clock, Duration retention, MongoDatabase database) {
        this.mongo = mongo;
        this.registry = registry;
        this.clock = clock;
        this.retention = retention == null || retention.isNegative() || retention.isZero() ? Duration.ofDays(InMemoryTelemetry.RETENTION_DAYS) : retention;
        mongo.index(COLLECTION, Indexes.ascending("atNs", "_id"));
        mongo.index(COLLECTION, Indexes.ascending("provider", "atNs", "_id"));
        if (database != null) {
            expireIn(database);
        }
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
        Bson after = Filters.gte("atNs", Mongo.nanos(from));
        Bson filter = provider == null ? after : Filters.and(Filters.eq("provider", provider), after);
        List<CallSample> out = new ArrayList<>();
        mongo.collection(COLLECTION).find(filter).projection(Projections.include(Mongo.DOC)).sort(OLDEST_FIRST)
                .forEach(d -> out.add(mongo.read(d, CallSample.class)));
        return out;
    }

    /** Writes every queued sample now. */
    public void flush() {
        synchronized (writing) {
            List<Document> batch = new ArrayList<>();
            for (CallSample s = queued.poll(); s != null; s = queued.poll()) {
                batch.add(document(s));
                if (batch.size() == BATCH) {
                    insert(batch);
                    batch = new ArrayList<>();
                }
            }
            insert(batch);
        }
    }

    /** Deletes the samples older than the retention; returns how many. */
    public int purge() {
        long deleted = mongo.collection(COLLECTION).deleteMany(Filters.lt("atNs", Mongo.nanos(clock.instant().minus(retention)))).getDeletedCount();
        return (int) Math.min(deleted, Integer.MAX_VALUE);
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

    /**
     * A TTL index on {@code at} whose lifetime is the retention, so MongoDB deletes an expired
     * sample itself (by the server's clock, on a pass about once a minute) whether or not an
     * instance runs its purge. Created unless it exists; when it exists with another lifetime (the
     * retention changed since the last start), the lifetime is changed in place with
     * {@code collMod}, a command of the database, since createIndex refuses changed options.
     * MongoDB takes at most 2^31 - 1 seconds (about 68 years); a longer retention is capped there.
     */
    private void expireIn(MongoDatabase database) {
        long seconds = Math.min(retention.toSeconds(), Integer.MAX_VALUE);
        try {
            mongo.index(COLLECTION, Indexes.ascending("at"), new IndexOptions().expireAfter(seconds, TimeUnit.SECONDS));
        } catch (MongoCommandException e) {
            if (e.getErrorCode() != INDEX_OPTIONS_CONFLICT) {
                throw e;
            }
            Document changed = database.runCommand(new Document("collMod", COLLECTION)
                    .append("index", new Document("keyPattern", new Document("at", 1)).append("expireAfterSeconds", seconds)));
            log.info("provider-call samples now expire {}s after their call (were {}s)", seconds, changed.get("expireAfterSeconds_old"));
        }
    }

    private Document document(CallSample s) {
        return new Document("_id", new ObjectId())
                .append("at", Mongo.date(s.at()))
                .append("atNs", Mongo.nanos(s.at()))
                .append("provider", s.provider())
                .append(Mongo.DOC, mongo.doc(s));
    }

    private void insert(List<Document> batch) {
        if (!batch.isEmpty()) {
            mongo.collection(COLLECTION).insertMany(batch);
        }
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

    private static String tag(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
