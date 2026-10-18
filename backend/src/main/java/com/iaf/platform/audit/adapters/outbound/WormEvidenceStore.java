package com.iaf.platform.audit.adapters.outbound;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.iaf.platform.audit.domain.EvidenceEntry;
import com.iaf.platform.audit.domain.EvidenceFilter;
import com.iaf.platform.audit.domain.EvidenceRecord;
import com.iaf.platform.audit.domain.HashChain;
import com.iaf.platform.audit.domain.SealedRecordException;
import com.iaf.platform.audit.domain.ShareToken;
import com.iaf.platform.audit.ports.outbound.EvidenceStore;
import com.iaf.platform.common.domain.NotFoundException;

import lombok.extern.slf4j.Slf4j;

/**
 * The evidence store on write-once objects ({@link WormObjects}): nothing is ever rewritten, so an
 * entry, once stored, stays exactly as it was chained. Layout:
 *
 * <pre>
 * evidence/&lt;record&gt;/entries/&lt;seq&gt;.json      one chained entry per object (seq zero-padded to 10 digits)
 * evidence/&lt;record&gt;/checkpoints/&lt;seq&gt;.json  an hourly signature over the head entry {@code seq} of an open record
 * evidence/&lt;record&gt;/seal.json               the final seal of a closed record
 * share-tokens/&lt;token&gt;.json                 a read-only link, kept until it expires
 * </pre>
 *
 * <p>{@code <record>} is the job id (or {@code platform}, {@code release:<id>}) with every character
 * but letters, digits, {@code -} and {@code _} percent-encoded. Every object is written with a
 * conditional create: of two writers of the same {@code seq} one wins, and the other re-reads the
 * record and chains after it, so the chain stays linear across instances. Each object is kept
 * {@code platform.audit.retention-years} after it was written.
 *
 * <p>The {@link EvidenceRecord} is a projection over the objects of one record, cached here. Reading
 * one record lists its objects and fetches the ones the cache has not seen; a listing discovers new
 * records and reuses the cached ones. Subclasses pick the objects and carry the profile.
 */
@Slf4j
public abstract class WormEvidenceStore implements EvidenceStore {

    static final String EVIDENCE = "evidence/";
    static final String SHARE_TOKENS = "share-tokens/";
    static final String ENTRIES = "entries/";
    static final String CHECKPOINTS = "checkpoints/";
    static final String SEAL = "seal.json";

    private static final int CONFLICT_ATTEMPTS = 5;
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{16,128}");
    private static final Comparator<EvidenceRecord> NEWEST_FIRST = Comparator
            .comparing((EvidenceRecord r) -> r.at() == null ? Instant.EPOCH : r.at()).reversed()
            .thenComparing(EvidenceRecord::jobId);

    /** The on-object format: ISO instants, no nulls; independent of the API's mapper so stored bytes never change shape. */
    static final ObjectMapper JSON = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .defaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
            .build();

    private final WormObjects objects;
    private final int retentionYears;
    private final Map<String, EvidenceRecord> records = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final Map<String, ShareToken> tokens = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    protected WormEvidenceStore(WormObjects objects, int retentionYears) {
        if (retentionYears < 1) {
            throw new IllegalArgumentException("platform.audit.retention-years must be at least 1, not " + retentionYears);
        }
        this.objects = objects;
        this.retentionYears = retentionYears;
        log.info("Evidence is written once to {} and kept {} years", objects.describe(), retentionYears);
    }

    protected WormObjects objects() {
        return objects;
    }

    @Override
    public EvidenceEntry append(String jobId, EvidenceEntry entry) {
        String id = requireId(jobId);
        synchronized (lock(id)) {
            for (int attempt = 1;; attempt++) {
                EvidenceRecord current = current(id);
                if (current != null && sealed(id, current)) {
                    throw new SealedRecordException(id, entry.kind());
                }
                int seq = current == null ? 1 : lastSeq(current) + 1;
                String previous = current == null ? HashChain.GENESIS : current.latestHash();
                EvidenceEntry chained = stable(entry).chained(seq, previous);
                if (objects.putIfAbsent(entryKey(id, seq), bytes(chained), retainUntil(chained.at()))) {
                    records.put(id, current == null ? EvidenceRecord.start(id, chained) : current.append(chained));
                    return chained;
                }
                // another writer took this seq: read what it wrote and chain after it
                records.remove(id);
                if (attempt >= CONFLICT_ATTEMPTS) {
                    throw new IllegalStateException("evidence record " + id + " kept changing under another writer; entry " + seq + " was not appended");
                }
                log.info("Evidence entry {} of {} was written by another instance; chaining after it", seq, id);
            }
        }
    }

    @Override
    public Optional<EvidenceRecord> record(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            return Optional.empty();
        }
        synchronized (lock(jobId)) {
            return Optional.ofNullable(refresh(jobId));
        }
    }

    @Override
    public List<EvidenceRecord> records(EvidenceFilter filter) {
        EvidenceFilter f = filter == null ? EvidenceFilter.ALL : filter;
        List<EvidenceRecord> all = new ArrayList<>();
        for (String folder : objects.folders(EVIDENCE)) {
            String id = decode(folder);
            EvidenceRecord r = records.get(id);
            if (r == null) {
                synchronized (lock(id)) {
                    r = current(id);
                }
            }
            if (r != null && f.matches(r)) {
                all.add(r);
            }
        }
        all.sort(NEWEST_FIRST);
        return all;
    }

    @Override
    public EvidenceRecord seal(String jobId, EvidenceRecord.Seal seal) {
        String id = requireId(jobId);
        synchronized (lock(id)) {
            EvidenceRecord current = current(id);
            if (current == null) {
                throw new NotFoundException("evidence record", id);
            }
            if (current.sealed() != null) {
                return current;
            }
            if (!objects.putIfAbsent(prefix(id) + SEAL, bytes(seal), retainUntil(seal.at()))) {
                records.remove(id);
                return refresh(id);
            }
            EvidenceRecord sealed = current.withSeal(seal);
            records.put(id, sealed);
            return sealed;
        }
    }

    @Override
    public EvidenceRecord checkpoint(String jobId, EvidenceRecord.Seal checkpoint) {
        String id = requireId(jobId);
        synchronized (lock(id)) {
            EvidenceRecord current = current(id);
            if (current == null) {
                throw new NotFoundException("evidence record", id);
            }
            int head = checkpoint.seq() == null ? lastSeq(current) : checkpoint.seq();
            if (!objects.putIfAbsent(prefix(id) + CHECKPOINTS + seqName(head), bytes(checkpoint), retainUntil(checkpoint.at()))) {
                return current; // this head is checkpointed already: the first signature stands
            }
            EvidenceRecord updated = current.withCheckpoint(checkpoint);
            records.put(id, updated);
            return updated;
        }
    }

    @Override
    public ShareToken shareToken(String jobId, Instant expiresAt) {
        for (int attempt = 1;; attempt++) {
            byte[] raw = new byte[24];
            random.nextBytes(raw);
            ShareToken token = new ShareToken(Base64.getUrlEncoder().withoutPadding().encodeToString(raw), jobId, expiresAt);
            if (objects.putIfAbsent(SHARE_TOKENS + token.token() + ".json", bytes(token), expiresAt)) {
                tokens.put(token.token(), token);
                return token;
            }
            if (attempt >= CONFLICT_ATTEMPTS) {
                throw new IllegalStateException("could not mint a unique share token");
            }
        }
    }

    @Override
    public Optional<ShareToken> findShareToken(String token) {
        if (token == null || !TOKEN.matcher(token).matches()) {
            return Optional.empty();
        }
        ShareToken known = tokens.get(token);
        if (known != null) {
            return Optional.of(known);
        }
        Optional<ShareToken> stored = objects.get(SHARE_TOKENS + token + ".json").map(b -> read(b, ShareToken.class));
        stored.ifPresent(t -> tokens.put(t.token(), t));
        return stored;
    }

    /** When an object written at {@code at} may go: {@code retention-years} later. */
    Instant retainUntil(Instant at) {
        Instant from = at == null ? Instant.now() : at;
        return from.atZone(ZoneOffset.UTC).plusYears(retentionYears).toInstant();
    }

    // ------------------------------------------------------------------ projection

    /** The cached record, else rebuilt from its objects; null when it has none. */
    private EvidenceRecord current(String id) {
        EvidenceRecord cached = records.get(id);
        return cached != null ? cached : refresh(id);
    }

    /** Lists the record's objects and rebuilds it, fetching only the objects the cache has not seen. */
    private EvidenceRecord refresh(String id) {
        EvidenceRecord known = records.get(id);
        Map<Integer, EvidenceEntry> seen = new HashMap<>();
        Map<Integer, EvidenceRecord.Seal> seenCheckpoints = new HashMap<>();
        if (known != null) {
            known.entries().forEach(e -> seen.put(e.seq(), e));
            known.checkpoints().forEach(c -> {
                if (c.seq() != null) {
                    seenCheckpoints.put(c.seq(), c);
                }
            });
        }
        String prefix = prefix(id);
        List<String> entryKeys = new ArrayList<>();
        List<String> checkpointKeys = new ArrayList<>();
        boolean sealed = false;
        for (String key : objects.list(prefix)) {
            String rest = key.substring(prefix.length());
            if (rest.startsWith(ENTRIES)) {
                entryKeys.add(key);
            } else if (rest.startsWith(CHECKPOINTS)) {
                checkpointKeys.add(key);
            } else if (rest.equals(SEAL)) {
                sealed = true;
            }
        }
        if (entryKeys.isEmpty()) {
            records.remove(id);
            return null;
        }
        EvidenceRecord rebuilt = null;
        for (String key : entryKeys) {
            int seq = seqOf(key);
            EvidenceEntry e = seen.containsKey(seq) ? seen.get(seq) : read(fetch(key), EvidenceEntry.class);
            rebuilt = rebuilt == null ? EvidenceRecord.start(id, e) : rebuilt.append(e);
        }
        for (String key : checkpointKeys) {
            int seq = seqOf(key);
            EvidenceRecord.Seal c = seenCheckpoints.containsKey(seq) ? seenCheckpoints.get(seq) : read(fetch(key), EvidenceRecord.Seal.class);
            rebuilt = rebuilt.withCheckpoint(c);
        }
        if (sealed) {
            EvidenceRecord.Seal seal = known != null && known.sealed() != null ? known.sealed() : read(fetch(prefix + SEAL), EvidenceRecord.Seal.class);
            rebuilt = rebuilt.withSeal(seal);
        }
        records.put(id, rebuilt);
        return rebuilt;
    }

    /** True when the record is sealed; a closed record not sealed here is checked for a seal another instance wrote. */
    private boolean sealed(String id, EvidenceRecord current) {
        if (current.sealed() != null) {
            return true;
        }
        if (!current.closed() || objects.get(prefix(id) + SEAL).isEmpty()) {
            return false;
        }
        records.remove(id);
        refresh(id);
        return true;
    }

    private byte[] fetch(String key) {
        return objects.get(key).orElseThrow(() -> new IllegalStateException("evidence object " + key + " was listed but cannot be read"));
    }

    // ------------------------------------------------------------------ keys and bytes

    static String prefix(String id) {
        return EVIDENCE + encode(id) + "/";
    }

    static String entryKey(String id, int seq) {
        return prefix(id) + ENTRIES + seqName(seq);
    }

    private static String seqName(int seq) {
        return String.format("%010d.json", seq);
    }

    private static int seqOf(String key) {
        String name = key.substring(key.lastIndexOf('/') + 1);
        return Integer.parseInt(name.substring(0, name.indexOf('.')));
    }

    private static int lastSeq(EvidenceRecord r) {
        return r.entries().isEmpty() ? 0 : r.entries().get(r.entries().size() - 1).seq();
    }

    /** Letters, digits, {@code -} and {@code _} stay; every other byte becomes {@code %XX}, so an id is always one safe path segment. */
    static String encode(String id) {
        StringBuilder out = new StringBuilder();
        for (byte b : id.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_') {
                out.append(c);
            } else {
                out.append('%').append(String.format("%02X", b & 0xff));
            }
        }
        return out.toString();
    }

    static String decode(String segment) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c == '%' && i + 2 < segment.length()) {
                out.write(Integer.parseInt(segment.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                out.write(c);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String requireId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("an evidence record needs an id");
        }
        return id;
    }

    private Object lock(String id) {
        return locks.computeIfAbsent(id, k -> new Object());
    }

    /** The entry as it will read back from its object, so the hash chained now is the hash verified later. */
    private static EvidenceEntry stable(EvidenceEntry e) {
        if (e.data() == null) {
            return e;
        }
        try {
            JsonNode data = JSON.readTree(JSON.writeValueAsBytes(e.data()));
            return new EvidenceEntry(e.seq(), e.at(), e.kind(), e.actor(), data, e.hash(), e.previous());
        } catch (IOException ex) {
            throw new IllegalArgumentException("evidence data is not JSON: " + ex.getMessage(), ex);
        }
    }

    static byte[] bytes(Object value) {
        try {
            return JSON.writeValueAsBytes(value);
        } catch (IOException e) {
            throw new IllegalArgumentException("not serialisable: " + e.getMessage(), e);
        }
    }

    static <T> T read(byte[] bytes, Class<T> type) {
        try {
            return JSON.readValue(bytes, type);
        } catch (IOException e) {
            throw new IllegalStateException("unreadable evidence object (" + type.getSimpleName() + "): " + e.getMessage(), e);
        }
    }
}
