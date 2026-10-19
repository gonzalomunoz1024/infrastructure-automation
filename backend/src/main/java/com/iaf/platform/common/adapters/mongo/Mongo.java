package com.iaf.platform.common.adapters.mongo;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.bson.BsonBinary;
import org.bson.BsonBinarySubType;
import org.bson.BsonInvalidOperationException;
import org.bson.BsonReader;
import org.bson.BsonType;
import org.bson.BsonWriter;
import org.bson.Document;
import org.bson.Transformer;
import org.bson.codecs.BsonTypeClassMap;
import org.bson.codecs.Codec;
import org.bson.codecs.CollectionCodecProvider;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.DocumentCodecProvider;
import org.bson.codecs.EncoderContext;
import org.bson.codecs.IterableCodecProvider;
import org.bson.codecs.MapCodecProvider;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.conversions.Bson;
import org.bson.types.Binary;
import org.bson.types.Decimal128;
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
 * mapping is the application's JSON (ISO instants to the nanosecond, lowercase enums, a null
 * component left out, while a null inside a JSON value or a map is kept both ways, so a job's input
 * or an evidence entry's hashed data comes back exactly, and half a surrogate pair stored as the
 * U+FFFD it would read back as) turned into a document field by field,
 * never parsed as extended JSON, so a key such as
 * {@code $ref} in an imported schema stays a key, and read back with {@link #codecs}, so a part of
 * a schema that holds {@code $id} beside {@code $ref} stays a document and an integer beyond a long
 * comes back as that integer, however long. Unknown fields are ignored on the way back, so a document written
 * before a record grew a field still reads, and records are their components (no is-getter
 * discovery, so {@code Job.isBatch()} does not shadow {@code batch}). Instants that a query
 * compares are also kept as epoch nanoseconds ({@link #nanos}), which order exactly.
 */
@Slf4j
public class Mongo {

    /** The field that holds the stored record. */
    public static final String DOC = "doc";

    // Only a null component is left out: the builder's serializationInclusion would also leave out a
    // map's null values, and a read turns the stored document, which is a map, into the record.
    private static final ObjectMapper MAPPER = Jackson2ObjectMapperBuilder.json()
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS,
                    DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .visibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.NONE)
            .build()
            .setDefaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.ALWAYS));
    private static final TypeReference<Map<String, Object>> FIELDS = new TypeReference<>() {
    };
    private static final String REVISIONS = "store_revisions";
    private static final String SEEDS = "store_seeds";

    private final MongoDatabase database;
    private final AtomicLong onNonBlockingThreads = new AtomicLong();

    public Mongo(MongoDatabase database) {
        this.database = database.withCodecRegistry(codecs(database.getCodecRegistry()));
    }

    /**
     * The codecs documents are written and read with: the driver's, with two changes so a record
     * comes back as stored. A sub-document stays a document: the driver reads one that has both
     * {@code $ref} and {@code $id} keys as a DBRef, dropping its other keys, and a JSON schema may
     * well have both. And an integer beyond a long (an OpenAPI {@code uint64} bound, or any number
     * in a run's input or a step's response), which the driver cannot write, is stored as a
     * Decimal128 up to 34 digits and, longer, as a binary of the user-defined subtype holding its
     * decimal digits ({@link #LONG_INTEGER}); either reads back as that integer, a BigInteger.
     */
    static CodecRegistry codecs(CodecRegistry driver) {
        BsonTypeClassMap types = new BsonTypeClassMap(Map.of(BsonType.DECIMAL128, Number.class));
        Transformer longIntegers = Mongo::longInteger;
        return CodecRegistries.fromRegistries(
                CodecRegistries.fromCodecs(new BigIntegerCodec(), new DecimalCodec()),
                CodecRegistries.fromProviders(new DocumentCodecProvider(types, longIntegers), new CollectionCodecProvider(types, longIntegers),
                        new IterableCodecProvider(types, longIntegers), new MapCodecProvider(types, longIntegers)),
                driver);
    }

    /** The binary subtype an integer too long for a Decimal128 is stored as: its decimal digits, an optional minus sign first, in ASCII. */
    static final byte LONG_INTEGER = BsonBinarySubType.USER_DEFINED.getValue();
    /** The most digits a Decimal128 holds exactly. */
    private static final int DECIMAL128_DIGITS = 34;

    /** A stored value as read: a binary holding a long integer's digits becomes that integer, anything else stays as it is. */
    private static Object longInteger(Object value) {
        if (value instanceof Binary b && b.getType() == LONG_INTEGER) {
            BigInteger integer = digits(b.getData());
            return integer == null ? value : integer;
        }
        return value;
    }

    /** The integer these ASCII digits (an optional minus sign first) spell; null when they spell none. */
    private static BigInteger digits(byte[] data) {
        int start = data.length > 1 && data[0] == '-' ? 1 : 0;
        if (data.length == start) {
            return null;
        }
        for (int i = start; i < data.length; i++) {
            if (data[i] < '0' || data[i] > '9') {
                return null;
            }
        }
        return new BigInteger(new String(data, StandardCharsets.US_ASCII));
    }

    /** An integer beyond a long: a Decimal128 up to 34 digits, else a {@link #LONG_INTEGER} binary of its digits. */
    private static final class BigIntegerCodec implements Codec<BigInteger> {

        @Override
        public void encode(BsonWriter writer, BigInteger value, EncoderContext context) {
            BigDecimal exact = new BigDecimal(value);
            if (exact.precision() <= DECIMAL128_DIGITS) {
                writer.writeDecimal128(new Decimal128(exact));
            } else {
                writer.writeBinaryData(new BsonBinary(LONG_INTEGER, value.toString().getBytes(StandardCharsets.US_ASCII)));
            }
        }

        @Override
        public BigInteger decode(BsonReader reader, DecoderContext context) {
            if (reader.getCurrentBsonType() == BsonType.BINARY && reader.peekBinarySubType() == LONG_INTEGER) {
                BigInteger integer = digits(reader.readBinaryData().getData());
                if (integer == null) {
                    throw new BsonInvalidOperationException("a binary of subtype " + LONG_INTEGER + " that holds no integer");
                }
                return integer;
            }
            return reader.readDecimal128().bigDecimalValue().toBigIntegerExact();
        }

        @Override
        public Class<BigInteger> getEncoderClass() {
            return BigInteger.class;
        }
    }

    /** A stored Decimal128 as a number: the integer it holds, else a BigDecimal (NaN, the infinities and -0 stay a Decimal128). */
    private static final class DecimalCodec implements Codec<Number> {

        @Override
        public void encode(BsonWriter writer, Number value, EncoderContext context) {
            writer.writeDecimal128(value instanceof Decimal128 d ? d : new Decimal128(new BigDecimal(value.toString())));
        }

        @Override
        public Number decode(BsonReader reader, DecoderContext context) {
            Decimal128 stored = reader.readDecimal128();
            if (!stored.isFinite()) {
                return stored;
            }
            BigDecimal value;
            try {
                value = stored.bigDecimalValue();
            } catch (ArithmeticException negativeZero) {
                return stored;
            }
            return value.scale() <= 0 ? value.toBigIntegerExact() : value;
        }

        @Override
        public Class<Number> getEncoderClass() {
            return Number.class;
        }
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

    /** A record as the document it is stored as, its text {@linkplain #wellFormed(String) well-formed}. */
    public Document doc(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new Document(wellFormed(MAPPER.convertValue(value, FIELDS)));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("cannot store " + value.getClass().getSimpleName() + " as a document: " + e.getMessage(), e);
        }
    }

    /**
     * The text with each lone UTF-16 surrogate (half a pair, which JSON may escape and Jackson
     * accepts) replaced by U+FFFD: the driver would write one as bytes that are not UTF-8, which
     * read back as U+FFFD, so storing U+FFFD keeps what is written equal to what reads back (and an
     * evidence entry's hash equal to the hash of what is read). The same string when it has none.
     */
    public static String wellFormed(String text) {
        if (text == null) {
            return null;
        }
        StringBuilder out = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isSurrogate(c)) {
                if (out != null) {
                    out.append(c);
                }
            } else if (Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                if (out != null) {
                    out.append(c).append(text.charAt(i + 1));
                }
                i++;
            } else {
                if (out == null) {
                    out = new StringBuilder(text.length()).append(text, 0, i);
                }
                out.append('�');
            }
        }
        return out == null ? text : out.toString();
    }

    /** The map's keys and text, at every depth, {@linkplain #wellFormed(String) well-formed}; nulls kept. */
    private static Map<String, Object> wellFormed(Map<?, ?> fields) {
        Map<String, Object> out = LinkedHashMap.newLinkedHashMap(fields.size());
        fields.forEach((k, v) -> out.put(wellFormed(String.valueOf(k)), wellFormedValue(v)));
        return out;
    }

    private static Object wellFormedValue(Object value) {
        if (value instanceof String s) {
            return wellFormed(s);
        }
        if (value instanceof Map<?, ?> m) {
            return wellFormed(m);
        }
        if (value instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object v : l) {
                out.add(wellFormedValue(v));
            }
            return out;
        }
        return value;
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
