package com.iaf.platform.common.adapters.mongo;

import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;

import lombok.extern.slf4j.Slf4j;
import reactor.core.scheduler.Schedulers;

/**
 * What the MongoDB adapters of the {@code mongo} profile share: the collections of one database,
 * the mapping of a domain record to the document it is stored as, the store revisions and the
 * one-off seeds, and a guard that reports a call made on a non-blocking thread (a Netty event loop
 * or a Reactor timer), where it would hold up every request on that thread. Built from a
 * {@link MongoDatabase} alone, so the adapter tests need no Spring context.
 *
 * <p>A record is stored whole, as the {@code doc} field of its document, and read back from it;
 * the other top-level fields are copies of what the adapter queries, sorts and indexes on. The
 * mapping is the application's JSON (ISO instants to the nanosecond, lowercase enums, no nulls)
 * turned into a document field by field, never parsed as extended JSON, so a key such as
 * {@code $ref} in an imported schema stays a key. Unknown fields are ignored on the way back, so a
 * document written before a record grew a field still reads, and records are their components (no
 * is-getter discovery, so {@code Job.isBatch()} does not shadow {@code batch}). Instants that a
 * query compares are also kept as epoch nanoseconds ({@link #nanos}), which order exactly.
 */
@Slf4j
public class Mongo {

    /** The field that holds the stored record. */
    public static final String DOC = "doc";

    private static final ObjectMapper MAPPER = Jackson2ObjectMapperBuilder.json()
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS,
                    DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .visibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.NONE)
            .build();
    private static final TypeReference<Map<String, Object>> FIELDS = new TypeReference<>() {
    };
    private static final String REVISIONS = "store_revisions";
    private static final String SEEDS = "store_seeds";

    private final MongoDatabase database;
    private final AtomicLong onNonBlockingThreads = new AtomicLong();

    public Mongo(MongoDatabase database) {
        this.database = database;
    }

    /** The mapper between records and their documents. */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** The database's name. */
    public String name() {
        return database.getName();
    }

    /** A collection of this database; call it per operation, so the guard sees where each call runs. */
    public MongoCollection<Document> collection(String name) {
        guard();
        return database.getCollection(name);
    }

    /** Create an index unless it exists (the same keys and options); safe on every start. */
    public void index(String collection, Bson keys, IndexOptions options) {
        collection(collection).createIndex(keys, options);
    }

    public void index(String collection, Bson keys) {
        index(collection, keys, new IndexOptions());
    }

    // ---------------------------------------------------------------- records

    /** A record as the document it is stored as. */
    public Document doc(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new Document(MAPPER.convertValue(value, FIELDS));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("cannot store " + value.getClass().getSimpleName() + " as a document: " + e.getMessage(), e);
        }
    }

    /** The record in the {@value #DOC} field of a stored document. */
    public <T> T read(Document stored, Class<T> type) {
        return as(stored.get(DOC, Document.class), type);
    }

    public <T> T read(Document stored, TypeReference<T> type) {
        return as(stored.get(DOC, Document.class), type);
    }

    /** A document (or a part of one) as a record. */
    public <T> T as(Document fields, Class<T> type) {
        try {
            return MAPPER.convertValue(fields, type);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("cannot read a stored " + type.getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    public <T> T as(Document fields, TypeReference<T> type) {
        try {
            return MAPPER.convertValue(fields, type);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("cannot read a stored " + type.getType().getTypeName() + ": " + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- values

    /** An instant as epoch nanoseconds, for exact ordering and ranges; null stays null. */
    public static Long nanos(Instant at) {
        return at == null ? null : Math.addExact(Math.multiplyExact(at.getEpochSecond(), 1_000_000_000L), at.getNano());
    }

    /** An instant as a BSON date (milliseconds), for a TTL index or for reading the data by hand; null stays null. */
    public static Date date(Instant at) {
        return at == null ? null : Date.from(at);
    }

    /** True when a write failed only because the key exists (a unique index or {@code _id}). */
    public static boolean duplicateKey(MongoException e) {
        return e instanceof MongoWriteException w && w.getError().getCategory() == ErrorCategory.DUPLICATE_KEY
                || ErrorCategory.fromErrorCode(e.getCode()) == ErrorCategory.DUPLICATE_KEY;
    }

    // ---------------------------------------------------------------- store revisions and seeds

    /** The revision of a store: it moves on every write to that store. */
    public long revision(String store) {
        Document d = collection(REVISIONS).find(Filters.eq("_id", store)).first();
        return d == null ? 0L : ((Number) d.get("revision")).longValue();
    }

    /** Move a store's revision; call it after the write it records, so a reader never keeps the old data under the new number. */
    public void bump(String store) {
        collection(REVISIONS).updateOne(Filters.eq("_id", store), Updates.inc("revision", 1L), new UpdateOptions().upsert(true));
    }

    /** Claim a one-off seed for this database: true the first time, false once any instance has claimed it. */
    public boolean claimSeed(String name, Instant at) {
        try {
            collection(SEEDS).insertOne(new Document("_id", name).append("seededAt", date(at)).append("seededAtNs", nanos(at)));
            return true;
        } catch (MongoException e) {
            if (duplicateKey(e)) {
                return false;
            }
            throw e;
        }
    }

    /** When a seed was claimed, if it was. */
    public Optional<Instant> seededAt(String name) {
        Document d = collection(SEEDS).find(Filters.eq("_id", name)).first();
        if (d == null) {
            return Optional.empty();
        }
        long ns = ((Number) d.get("seededAtNs")).longValue();
        return Optional.of(Instant.ofEpochSecond(Math.floorDiv(ns, 1_000_000_000L), Math.floorMod(ns, 1_000_000_000L)));
    }

    // ---------------------------------------------------------------- the guard

    /** How many calls were made on a non-blocking thread since this instance was built (for tests and diagnosis). */
    public long callsOnNonBlockingThreads() {
        return onNonBlockingThreads.get();
    }

    private void guard() {
        if (!Schedulers.isInNonBlockingThread()) {
            return;
        }
        long n = onNonBlockingThreads.incrementAndGet();
        if (n == 1) {
            log.warn("a MongoDB call ran on the non-blocking thread {}; blocking store calls belong on Schedulers.boundedElastic()",
                    Thread.currentThread().getName(), new IllegalStateException("the call site"));
        } else if (n % 1000 == 0) {
            log.warn("{} MongoDB calls have run on non-blocking threads (latest on {})", n, Thread.currentThread().getName());
        }
    }
}
