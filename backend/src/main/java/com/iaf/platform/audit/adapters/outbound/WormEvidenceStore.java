package com.iaf.platform.audit.adapters.outbound;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import com.iaf.platform.common.domain.Hashes;
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
 * sealed/&lt;record&gt;.json                       an index entry per sealed record, so a sealing run never reads one
 * share-tokens/&lt;sha256&gt;.json                a read-only link under the SHA-256 of its token (never the token), kept until it expires
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
 * records and reuses the cached ones. The cache is bounded, least recently used out first, and a
 * record that left it is read back from its objects when asked for again: open records (which take
 * appends) are kept {@link #OPEN_CACHE} at most, so a record another instance appends to or a
 * release record never closed does not stay for the life of the process, and sealed ones, which
 * never change, {@link #SEALED_CACHE} at most. Share links are cached by the hash
 * of their token, {@link #TOKEN_CACHE} at most, and an expired one is dropped from the cache when it
 * is found. Records are locked through a fixed set of stripes, so the locks do not grow with the
 * records. Subclasses pick the objects and carry the profile.
 */
@Slf4j
public abstract class WormEvidenceStore implements EvidenceStore {

    static final String EVIDENCE = "evidence/";
    static final String SHARE_TOKENS = "share-tokens/";
    static final String SEALED_INDEX = "sealed/";
    static final String ENTRIES = "entries/";
    static final String CHECKPOINTS = "checkpoints/";
    static final String SEAL = "seal.json";

    private static final int CONFLICT_ATTEMPTS = 5;
    /** Open records kept in memory; more are read back from their objects. */
    static final int OPEN_CACHE = 1024;
    /** Sealed records kept in memory; more are read back from their objects. */
    static final int SEALED_CACHE = 256;
    /** Share links kept in memory, by the hash of their token. */
    static final int TOKEN_CACHE = 1024;
    private static final int LOCK_STRIPES = 256;
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
    private final Clock clock;
    /** Records still taking entries, least recently used first; guarded by itself. */
    private final Map<String, EvidenceRecord> open;
    /** Sealed records, least recently used first; guarded by itself. */
    private final Map<String, EvidenceRecord> sealedRecords;
    /** Share links by the SHA-256 of their token, without the token; guarded by itself. */
    private final Map<String, ShareToken> tokens;
    private final Object[] stripes = new Object[LOCK_STRIPES];
    private final SecureRandom random = new SecureRandom();

    protected WormEvidenceStore(WormObjects objects, int retentionYears) {
        this(objects, retentionYears, Clock.systemUTC(), SEALED_CACHE);
    }

    protected WormEvidenceStore(WormObjects objects, int retentionYears, Clock clock) {
        this(objects, retentionYears, clock, SEALED_CACHE);
    }

    WormEvidenceStore(WormObjects objects, int retentionYears, Clock clock, int sealedCache) {
        this(objects, retentionYears, clock, sealedCache, OPEN_CACHE);
    }

    WormEvidenceStore(WormObjects objects, int retentionYears, Clock clock, int sealedCache, int openCache) {
        if (retentionYears < 1) {
            throw new IllegalArgumentException("platform.audit.retention-years must be at least 1, not " + retentionYears);
        }
        this.objects = objects;
        this.retentionYears = retentionYears;
        this.clock = clock;
        this.open = lru(openCache);
        this.sealedRecords = lru(sealedCache);
        this.tokens = lru(TOKEN_CACHE);
        for (int i = 0; i < stripes.length; i++) {
            stripes[i] = new Object();
        }
        log.info("Evidence is written once to {} and kept {} years", objects.describe(), retentionYears);
    }

    private static <V> Map<String, V> lru(int max) {
        return new Lru<>(max);
    }

    /** Access-ordered, at most {@code max} entries, the least recently used out first; not thread-safe on its own. */
    private static final class Lru<V> extends LinkedHashMap<String, V> {

        private static final long serialVersionUID = 1L;
        private final int max;

        Lru(int max) {
            super(16, 0.75f, true);
            this.max = max;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
            return size() > max;
        }
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
                    cache(id, current == null ? EvidenceRecord.start(id, chained) : current.append(chained));
                    return chained;
                }
                // another writer took this seq: read what it wrote and chain after it
                uncache(id);
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
            EvidenceRecord r = cached(id);
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

    /** Each record is read (from the bounded cache or its objects) and let go: only its id and time are kept. */
    @Override
    public List<String> recordIds(EvidenceFilter filter) {
        EvidenceFilter f = filter == null ? EvidenceFilter.ALL : filter;
        List<Map.Entry<Instant, String>> found = new ArrayList<>();
        for (String folder : objects.folders(EVIDENCE)) {
            String id = decode(folder);
            EvidenceRecord r = cached(id);
            if (r == null) {
                synchronized (lock(id)) {
                    r = current(id);
                }
            }
            if (r != null && f.matches(r)) {
                found.add(Map.entry(r.at() == null ? Instant.EPOCH : r.at(), id));
            }
        }
        return found.stream()
                .sorted(Map.Entry.<Instant, String>comparingByKey().reversed().thenComparing(Map.Entry.comparingByValue()))
                .map(Map.Entry::getValue)
                .toList();
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
                uncache(id);
                EvidenceRecord theirs = refresh(id);
                index(id, theirs);
                return theirs;
            }
            EvidenceRecord sealed = current.withSeal(seal);
            cache(id, sealed);
            index(id, sealed);
            return sealed;
        }
    }

    /**
     * The records without an entry in the {@code sealed/} index: open ones come from the cache or
     * their objects, and a record sealed before the index existed is indexed the first time it is
     * found sealed, so later runs skip it without reading it.
     */
    @Override
    public List<Unsealed> unsealed() {
        Set<String> indexed = new HashSet<>();
        for (String key : objects.list(SEALED_INDEX)) {
            String name = key.substring(SEALED_INDEX.length());
            indexed.add(name.endsWith(".json") ? name.substring(0, name.length() - ".json".length()) : name);
        }
        List<Unsealed> out = new ArrayList<>();
        for (String folder : objects.folders(EVIDENCE)) {
            if (indexed.contains(folder)) {
                continue;
            }
            String id = decode(folder);
            EvidenceRecord r;
            synchronized (lock(id)) {
                r = current(id);
                if (r != null && r.sealed() == null && r.closed() && objects.get(prefix(id) + SEAL).isPresent()) {
                    uncache(id);
                    r = refresh(id);
                }
            }
            if (r == null || r.entries().isEmpty()) {
                continue;
            }
            if (r.sealed() != null) {
                index(id, r);
                continue;
            }
            out.add(Unsealed.of(r));
        }
        out.sort(Unsealed.NEWEST_FIRST);
        return out;
    }

    /** Notes a sealed record in the {@code sealed/} index (once; kept as long as its seal). */
    private void index(String id, EvidenceRecord sealed) {
        if (sealed == null || sealed.sealed() == null) {
            return;
        }
        byte[] entry = bytes(Map.of("jobId", id, "sealedAt", String.valueOf(sealed.sealed().at())));
        objects.putIfAbsent(SEALED_INDEX + encode(id) + ".json", entry, retainUntil(sealed.sealed().at()));
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
            cache(id, updated);
            return updated;
        }
    }

    /** Mints a link and keeps it under the SHA-256 of its token; the object never holds the token. */
    @Override
    public ShareToken shareToken(String jobId, Instant expiresAt) {
        forgetExpiredTokens();
        for (int attempt = 1;; attempt++) {
            byte[] raw = new byte[24];
            random.nextBytes(raw);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
            String hash = Hashes.sha256(token);
            ShareToken kept = new ShareToken(null, jobId, expiresAt);
            if (objects.putIfAbsent(tokenKey(hash), bytes(kept), expiresAt)) {
                synchronized (tokens) {
                    tokens.put(hash, kept);
                }
                return new ShareToken(token, jobId, expiresAt);
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
        String hash = Hashes.sha256(token);
        ShareToken link;
        synchronized (tokens) {
            link = tokens.get(hash);
        }
        if (link == null) {
            // a link minted before tokens were kept by their hash still sits under the token itself
            link = objects.get(tokenKey(hash)).or(() -> objects.get(SHARE_TOKENS + token + ".json"))
                    .map(b -> read(b, ShareToken.class))
                    .orElse(null);
            if (link == null) {
                return Optional.empty();
            }
        }
        ShareToken found = new ShareToken(token, link.jobId(), link.expiresAt());
        synchronized (tokens) {
            if (found.expired(clock.instant())) {
                tokens.remove(hash);
            } else {
                tokens.put(hash, new ShareToken(null, link.jobId(), link.expiresAt()));
            }
        }
        return Optional.of(found);
    }

    static String tokenKey(String tokenHash) {
        return SHARE_TOKENS + tokenHash + ".json";
    }

    private void forgetExpiredTokens() {
        Instant now = clock.instant();
        synchronized (tokens) {
            tokens.values().removeIf(t -> t.expired(now));
        }
    }

    /** How many records and share links are in memory (for tests and diagnosis). */
    int cachedRecords() {
        synchronized (open) {
            synchronized (sealedRecords) {
                return open.size() + sealedRecords.size();
            }
        }
    }

    int cachedTokens() {
        synchronized (tokens) {
            return tokens.size();
        }
    }

    /** When an object written at {@code at} may go: {@code retention-years} later. */
    Instant retainUntil(Instant at) {
        Instant from = at == null ? clock.instant() : at;
        return from.atZone(ZoneOffset.UTC).plusYears(retentionYears).toInstant();
    }

    // ------------------------------------------------------------------ projection

    /** The cached record, else rebuilt from its objects; null when it has none. */
    private EvidenceRecord current(String id) {
        EvidenceRecord cached = cached(id);
        return cached != null ? cached : refresh(id);
    }

    private EvidenceRecord cached(String id) {
        EvidenceRecord r;
        synchronized (open) {
            r = open.get(id);
        }
        if (r != null) {
            return r;
        }
        synchronized (sealedRecords) {
            return sealedRecords.get(id);
        }
    }

    /** An open record goes to the open cache; a sealed one moves to the sealed cache. */
    private void cache(String id, EvidenceRecord record) {
        synchronized (open) {
            if (record.sealed() == null) {
                open.put(id, record);
                return;
            }
            open.remove(id);
        }
        synchronized (sealedRecords) {
            sealedRecords.put(id, record);
        }
    }

    private void uncache(String id) {
        synchronized (open) {
            open.remove(id);
        }
        synchronized (sealedRecords) {
            sealedRecords.remove(id);
        }
    }

    /** Lists the record's objects and rebuilds it, fetching only the objects the cache has not seen. */
    private EvidenceRecord refresh(String id) {
        EvidenceRecord known = cached(id);
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
            uncache(id);
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
        cache(id, rebuilt);
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
        uncache(id);
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

    /** The record's lock stripe: never held while another stripe is taken, so stripes shared by two ids only serialise them. */
    private Object lock(String id) {
        return stripes[Math.floorMod(id.hashCode(), stripes.length)];
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
