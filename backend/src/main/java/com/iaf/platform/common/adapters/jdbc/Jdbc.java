package com.iaf.platform.common.adapters.jdbc;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import lombok.extern.slf4j.Slf4j;
import reactor.core.scheduler.Schedulers;

/**
 * What the JDBC adapters of the {@code postgres} profile share: statements and transactions over
 * one {@link DataSource}, the JSON mapper of the {@code doc} columns, a portable upsert, the store
 * revisions, and a guard that reports a statement run on a non-blocking thread (a Netty event loop
 * or a Reactor timer), where it would hold up every request on that thread. Built from a
 * {@code DataSource} alone, so the adapter tests need no Spring context.
 *
 * <p>The mapper is the application's (ISO instants to the nanosecond, lowercase enums, no nulls)
 * with the payload rules of the Temporal converter: unknown properties are ignored, so a row
 * written before a record grew a field still reads, and records are their components (no
 * is-getter discovery, so {@code Job.isBatch()} does not shadow {@code batch}).
 */
@Slf4j
public class Jdbc {

    private static final ObjectMapper MAPPER = Jackson2ObjectMapperBuilder.json()
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS,
                    DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .visibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.NONE)
            .build();

    private final JdbcTemplate template;
    private final TransactionTemplate transactions;
    private final AtomicLong onNonBlockingThreads = new AtomicLong();

    public Jdbc(DataSource dataSource) {
        this.template = new JdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    /** The JSON mapper of the {@code doc} columns. */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    // ---------------------------------------------------------------- statements

    public <T> T inTransaction(Supplier<T> work) {
        guard();
        return transactions.execute(status -> work.get());
    }

    public void inTransaction(Runnable work) {
        guard();
        transactions.executeWithoutResult(status -> work.run());
    }

    public <T> List<T> query(String sql, RowMapper<T> rows, Object... args) {
        guard();
        return template.query(sql, rows, args);
    }

    public <T> Optional<T> first(String sql, RowMapper<T> rows, Object... args) {
        return query(sql, rows, args).stream().findFirst();
    }

    public int update(String sql, Object... args) {
        guard();
        return template.update(sql, args);
    }

    public void batch(String sql, List<Object[]> rows) {
        if (rows.isEmpty()) {
            return;
        }
        guard();
        template.batchUpdate(sql, rows);
    }

    /**
     * Update the row, or insert it when none matched. {@code insert} ends in
     * {@code ON CONFLICT DO NOTHING}, so a row inserted by someone else in between turns into the
     * update instead of an error (which on PostgreSQL would abort the whole transaction).
     */
    public void upsert(String update, Object[] updateArgs, String insert, Object[] insertArgs) {
        if (update(update, updateArgs) == 0 && update(insert, insertArgs) == 0) {
            update(update, updateArgs);
        }
    }

    /** Insert unless the key exists; true when this call inserted it. {@code insert} ends in {@code ON CONFLICT DO NOTHING}. */
    public boolean insertIfAbsent(String insert, Object... args) {
        return update(insert, args) > 0;
    }

    // ---------------------------------------------------------------- store revisions and seeds

    /** The revision of a store in {@code store_revisions}: it moves on every write to that store. */
    public long revision(String store) {
        return first("SELECT revision FROM store_revisions WHERE name = ?", (rs, i) -> rs.getLong(1), store).orElse(0L);
    }

    /** Move a store's revision; call it inside the writing transaction. */
    public void bump(String store) {
        if (update("UPDATE store_revisions SET revision = revision + 1 WHERE name = ?", store) == 0) {
            insertIfAbsent("INSERT INTO store_revisions (name, revision) VALUES (?, 1) ON CONFLICT DO NOTHING", store);
        }
    }

    /** Claim a one-off seed for this database: true the first time, false once any instance has claimed it. */
    public boolean claimSeed(String name, Instant at) {
        return insertIfAbsent("INSERT INTO store_seeds (name, seeded_at) VALUES (?, ?) ON CONFLICT DO NOTHING", name, time(at));
    }

    /** When a seed was claimed, if it was. */
    public Optional<Instant> seededAt(String name) {
        return first("SELECT seeded_at FROM store_seeds WHERE name = ?", (rs, i) -> rs.getObject(1, OffsetDateTime.class).toInstant(), name);
    }

    // ---------------------------------------------------------------- values

    public String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot store " + value.getClass().getSimpleName() + " as JSON: " + e.getOriginalMessage(), e);
        }
    }

    public <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot read a stored " + type.getSimpleName() + ": " + e.getOriginalMessage(), e);
        }
    }

    public <T> T read(String json, TypeReference<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot read a stored " + type.getType().getTypeName() + ": " + e.getOriginalMessage(), e);
        }
    }

    /** An instant for a TIMESTAMP WITH TIME ZONE column, which keeps microseconds; the exact value lives in the doc or a {@code _ns} column. */
    public static OffsetDateTime time(Instant at) {
        return at == null ? null : OffsetDateTime.ofInstant(at.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    /** An instant as epoch nanoseconds, for exact ordering and ranges. */
    public static Long nanos(Instant at) {
        return at == null ? null : Math.addExact(Math.multiplyExact(at.getEpochSecond(), 1_000_000_000L), at.getNano());
    }

    // ---------------------------------------------------------------- the guard

    /** How many statements ran on a non-blocking thread since this instance was built (for tests and diagnosis). */
    public long callsOnNonBlockingThreads() {
        return onNonBlockingThreads.get();
    }

    private void guard() {
        if (!Schedulers.isInNonBlockingThread()) {
            return;
        }
        long n = onNonBlockingThreads.incrementAndGet();
        if (n == 1) {
            log.warn("a JDBC statement ran on the non-blocking thread {}; blocking store calls belong on Schedulers.boundedElastic()",
                    Thread.currentThread().getName(), new IllegalStateException("the call site"));
        } else if (n % 1000 == 0) {
            log.warn("{} JDBC statements have run on non-blocking threads (latest on {})", n, Thread.currentThread().getName());
        }
    }
}
