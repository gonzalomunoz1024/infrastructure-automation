package com.iaf.console.kit.mongo;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.bson.Document;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * What the MongoDB adapters of the {@code mongo} profile share, the platform's rules on the reactive
 * driver: one document per record with the record under {@code doc} and the queried fields beside
 * it, instants as ISO text inside the record and as BSON dates where an index or a TTL needs them,
 * sequences from {@code store_sequences}, and indexes created at start. Built from a
 * {@link MongoDatabase} alone, so the adapter tests need no Spring context.
 */
public final class Mongo {

    /** The field that holds the stored record. */
    public static final String DOC = "doc";
    static final String SEQUENCES = "store_sequences";

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .defaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.ALWAYS))
            .build();
    private static final TypeReference<Map<String, Object>> FIELDS = new TypeReference<>() {
    };

    private final MongoDatabase database;

    public Mongo(MongoDatabase database) {
        this.database = database;
    }

    /** The mapper between records and their documents. */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public String name() {
        return database.getName();
    }

    public MongoDatabase database() {
        return database;
    }

    public MongoCollection<Document> collection(String name) {
        return database.getCollection(name);
    }

    /** Creates the indexes that do not exist yet; safe on every start. */
    public Mono<Void> indexes(String collection, List<IndexModel> models) {
        if (models.isEmpty()) {
            return Mono.empty();
        }
        return Flux.from(collection(collection).createIndexes(models)).then();
    }

    /** The next value of a named counter, starting at 1. */
    public Mono<Long> nextSequence(String name) {
        return Mono.from(collection(SEQUENCES).findOneAndUpdate(Filters.eq("_id", name), Updates.inc("value", 1L),
                new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER)))
                .map(d -> ((Number) d.get("value")).longValue());
    }

    /** A record as the document it is stored as. */
    public Document doc(Object value) {
        if (value == null) {
            return null;
        }
        return new Document(MAPPER.convertValue(value, FIELDS));
    }

    /** The record in the {@value #DOC} field of a stored document. */
    public <T> T read(Document stored, Class<T> type) {
        return as(stored.get(DOC, Document.class), type);
    }

    /** A document (or a part of one) as a record. */
    public <T> T as(Document fields, Class<T> type) {
        return MAPPER.convertValue(fields, type);
    }

    /** An instant as epoch nanoseconds, for exact ordering and ranges; null stays null. */
    public static Long nanos(Instant at) {
        return at == null ? null : Math.addExact(Math.multiplyExact(at.getEpochSecond(), 1_000_000_000L), at.getNano());
    }

    /** Epoch nanoseconds back to an instant. */
    public static Instant fromNanos(long ns) {
        return Instant.ofEpochSecond(Math.floorDiv(ns, 1_000_000_000L), Math.floorMod(ns, 1_000_000_000L));
    }

    /** An instant as a BSON date (milliseconds), for a TTL index or a range the driver compares; null stays null. */
    public static Date date(Instant at) {
        return at == null ? null : Date.from(at);
    }

    public static Instant instant(Date d) {
        return d == null ? null : d.toInstant();
    }

    /** True when a write failed only because the key exists (a unique index or {@code _id}). */
    public static boolean duplicateKey(MongoException e) {
        return e instanceof MongoWriteException w && w.getError().getCategory() == ErrorCategory.DUPLICATE_KEY
                || ErrorCategory.fromErrorCode(e.getCode()) == ErrorCategory.DUPLICATE_KEY;
    }
}
