package com.iaf.platform.audit.adapters.outbound;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.platform.audit.domain.EvidenceEntry;
import com.iaf.platform.audit.domain.EvidenceFilter;
import com.iaf.platform.audit.domain.EvidenceKind;
import com.iaf.platform.audit.domain.EvidenceRecord;
import com.iaf.platform.audit.domain.HashChain;
import com.iaf.platform.audit.domain.SealedRecordException;
import com.iaf.platform.audit.domain.ShareToken;
import com.iaf.platform.audit.ports.outbound.EvidenceStore;
import com.iaf.platform.common.adapters.mongo.Mongo;
import com.iaf.platform.common.domain.Hashes;
import com.iaf.platform.common.domain.NotFoundException;
import com.mongodb.MongoException;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.BsonField;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;

import lombok.extern.slf4j.Slf4j;

/**
 * The evidence store of the {@code mongo} profile, unless a write-once store ({@code worm},
 * {@code worm-s3}) is active. A job's entries are documents of {@code evidence_entries} keyed by
 * {@code {jobId, seq}}, only ever inserted, under a unique index on the two: of two writers of the
 * same {@code seq} one wins, and the other reads what it wrote and chains after it, so the chain
 * never forks, across threads or instances. The {@code evidence_records} document is the header
 * (the record without its entries: what they add up to, how many it has counted, whether one
 * closed it, and the seal), which an append grows after inserting its entry and a seal fixes once,
 * with an update that only matches while the record has no seal. Checkpoints are documents of
 * {@code evidence_checkpoints} keyed by the record and the head they sign, also only inserted, so
 * the header stays small for the platform stream, checkpointed every hour for years. A share link
 * is a document of {@code evidence_share_links} under the SHA-256 of its token, never the token.
 *
 * <p>MongoDB has no triggers, so in production the database user's role is what refuses a change
 * or a delete of an entry (find and insert only on {@code evidence_entries}); this adapter never
 * asks for one. There are no multi-document transactions either, so an append inserts its entry
 * first and grows the header after, with an update that only moves the header forward; an append
 * cut off in between leaves a header that has not counted every entry, and so does a header update
 * that fails, which does not fail the append (its entry is in, and a retried append would chain the
 * same event twice). Every read folds the
 * entries the header has not counted ({@link #record}, {@link #records}); the record's next append
 * brings the header forward, and so does the sealing run ({@link #unsealed}), so a record closed
 * by such an entry is still sealed. Appends and seals of one record are serialised in this
 * instance; across instances an entry that races a seal is kept after it, as with the write-once
 * stores: the seal names the entry it covers ({@code seq}, {@code hash}), so verification still
 * checks exactly what was sealed.
 */
@Slf4j
@Component
@Primary
@Profile("mongo & !worm & !worm-s3")
public class MongoEvidenceStore implements EvidenceStore {

    static final String RECORDS = "evidence_records";
    static final String ENTRIES = "evidence_entries";
    static final String CHECKPOINTS = "evidence_checkpoints";
    static final String SHARE_LINKS = "evidence_share_links";

    /** How many times an append chains after another writer's entry before it gives up. */
    static final int CONFLICT_ATTEMPTS = 50;
    private static final int LOCK_STRIPES = 256;
    private static final Comparator<EvidenceRecord> NEWEST_FIRST = Comparator
            .comparing((EvidenceRecord r) -> r.at() == null ? Instant.EPOCH : r.at()).reversed()
            .thenComparing(EvidenceRecord::jobId);

    private final Mongo mongo;
    private final SecureRandom random = new SecureRandom();
    private final Object[] stripes = new Object[LOCK_STRIPES];

    public MongoEvidenceStore(Mongo mongo) {
        this.mongo = mongo;
        for (int i = 0; i < stripes.length; i++) {
            stripes[i] = new Object();
        }
        // unique on the parts too, so no document can take a taken seq whatever the order of the fields in its _id
        mongo.index(ENTRIES, Indexes.ascending("_id.jobId", "_id.seq"), new IndexOptions().unique(true));
        mongo.index(CHECKPOINTS, Indexes.ascending("_id.jobId", "_id.seq"), new IndexOptions().unique(true));
        mongo.index(RECORDS, Indexes.descending("requestedNs"));
    }

    @Override
    public EvidenceEntry append(String jobId, EvidenceEntry entry) {
        if (entry.kind() == null) {
            throw new IllegalArgumentException("an evidence entry needs its kind");
        }
        String id = requireId(jobId);
        EvidenceEntry stable = stable(entry);
        synchronized (lock(id)) {
            for (int attempt = 1;; attempt++) {
                Head head = head(id);
                if (head != null && head.seal() != null) {
                    throw new SealedRecordException(id, entry.kind());
                }
                EvidenceEntry chained = head == null ? stable.chained(1, HashChain.GENESIS) : stable.chained(head.count() + 1, head.record().latestHash());
                try {
                    mongo.collection(ENTRIES).insertOne(new Document("_id", key(id, chained.seq())).append(Mongo.DOC, mongo.doc(chained)));
                } catch (MongoException e) {
                    if (!Mongo.duplicateKey(e)) {
                        throw e;
                    }
                    if (attempt >= CONFLICT_ATTEMPTS) {
                        throw new IllegalStateException("evidence record " + id + " kept changing under other writers; entry " + chained.seq()
                                + " was not appended", e);
                    }
                    // another writer appended this seq first: read what it wrote and chain after it
                    log.debug("evidence entry {} of {} was written by another writer; chaining after it", chained.seq(), id);
                    continue;
                }
                // the entry is in, so the append has happened: a caller that retried a failure would chain the event twice
                try {
                    grow(id, head == null ? Head.open(id, chained) : head.then(chained));
                } catch (RuntimeException e) {
                    log.warn("evidence entry {} of {} was appended, but its record's header was not moved forward; "
                            + "reads fold the entry in and the next append or sealing run moves it", chained.seq(), id, e);
                }
                return chained;
            }
        }
    }

    @Override
    public Optional<EvidenceRecord> record(String jobId) {
        if (jobId == null) {
            return Optional.empty();
        }
        // the header first, then the entries: an entry it has not counted yet is folded in, never one it counted missed
        Document header = mongo.collection(RECORDS).find(Filters.eq("_id", jobId)).first();
        List<EvidenceEntry> entries = new ArrayList<>();
        mongo.collection(ENTRIES).find(Filters.eq("_id.jobId", jobId)).sort(Sorts.ascending("_id.seq"))
                .forEach(d -> entries.add(mongo.read(d, EvidenceEntry.class)));
        List<EvidenceRecord.Seal> checkpoints = new ArrayList<>();
        mongo.collection(CHECKPOINTS).find(Filters.eq("_id.jobId", jobId)).sort(Sorts.ascending("_id.seq"))
                .forEach(d -> checkpoints.add(mongo.read(d, EvidenceRecord.Seal.class)));
        return Optional.ofNullable(build(jobId, header, entries, checkpoints));
    }

    @Override
    public List<EvidenceRecord> records(EvidenceFilter filter) {
        EvidenceFilter f = filter == null ? EvidenceFilter.ALL : filter;
        Bson where = where(f);
        List<Document> headers = mongo.collection(RECORDS).find(where == null ? Filters.empty() : where).into(new ArrayList<>());
        if (headers.isEmpty()) {
            return List.of();
        }
        // every record wanted: no list of ids, which could outgrow the 16 MB a command may take
        Bson ofThese = where == null ? Filters.empty() : Filters.in("_id.jobId", headers.stream().map(h -> h.getString("_id")).toList());
        Map<String, List<EvidenceEntry>> entries = byRecord(ENTRIES, ofThese, d -> mongo.read(d, EvidenceEntry.class));
        Map<String, List<EvidenceRecord.Seal>> checkpoints = byRecord(CHECKPOINTS, ofThese, d -> mongo.read(d, EvidenceRecord.Seal.class));
        List<EvidenceRecord> out = new ArrayList<>(headers.size());
        for (Document h : headers) {
            String id = h.getString("_id");
            out.add(build(id, h, entries.getOrDefault(id, List.of()), checkpoints.getOrDefault(id, List.of())));
        }
        return out.stream().filter(f::matches).sorted(NEWEST_FIRST).toList();
    }

    /** The ids from the headers alone, newest first (a record with no request time where the epoch is), no entry read. */
    @Override
    public List<String> recordIds(EvidenceFilter filter) {
        EvidenceFilter f = filter == null ? EvidenceFilter.ALL : filter;
        List<Map.Entry<Long, String>> found = new ArrayList<>();
        Bson where = where(f);
        mongo.collection(RECORDS).find(where == null ? Filters.empty() : where).projection(Projections.include("requestedNs")).forEach(h -> {
            Long ns = h.getLong("requestedNs");
            found.add(Map.entry(ns == null ? 0L : ns, h.getString("_id")));
        });
        return found.stream()
                .sorted(Map.Entry.<Long, String>comparingByKey().reversed().thenComparing(Map.Entry.comparingByValue()))
                .map(Map.Entry::getValue)
                .toList();
    }

    /** The filter on the headers' fields (the same fields {@link EvidenceFilter#matches} reads off a record); null for every record. */
    private static Bson where(EvidenceFilter f) {
        List<Bson> where = new ArrayList<>();
        if (f.workflow() != null) {
            where.add(Filters.eq("workflow", f.workflow()));
        }
        if (f.requester() != null) {
            where.add(Filters.eq("requester", f.requester()));
        }
        if (f.from() != null) {
            where.add(Filters.gte("requestedNs", Mongo.nanos(f.from())));
        }
        if (f.to() != null) {
            where.add(Filters.lt("requestedNs", Mongo.nanos(f.to())));
        }
        return where.isEmpty() ? null : Filters.and(where);
    }

    /**
     * The headers of the records not sealed, with whether a {@code closed} entry exists and their
     * newest checkpoint: no entry document is read, and a sealed record is not touched. A header
     * that has not counted every entry of its record (an append cut off before its header moved)
     * is brought forward here, found from the index of the entries.
     */
    @Override
    public List<Unsealed> unsealed() {
        List<Document> headers = mongo.collection(RECORDS).find(Filters.and(Filters.exists("seal", false), Filters.gt("count", 0)))
                .into(new ArrayList<>());
        if (headers.isEmpty()) {
            return List.of();
        }
        List<String> ids = headers.stream().map(h -> h.getString("_id")).toList();
        Map<String, Document> lastEntries = last(ENTRIES, ids, Accumulators.first("seq", "$_id.seq"));
        Map<String, Document> lastCheckpoints = last(CHECKPOINTS, ids, Accumulators.first("latest", "$$ROOT"));
        List<Unsealed> out = new ArrayList<>(headers.size());
        for (Document h : headers) {
            String id = h.getString("_id");
            Head head = Head.of(mongo, h);
            Document lastEntry = lastEntries.get(id);
            if (lastEntry != null && ((Number) lastEntry.get("seq")).intValue() > head.count()) {
                synchronized (lock(id)) {
                    head = head(id);
                    if (head.seal() != null) {
                        continue;
                    }
                    grow(id, head);
                }
            }
            Document checkpoint = lastCheckpoints.get(id);
            EvidenceRecord.Seal latest = checkpoint == null ? null : mongo.read(checkpoint.get("latest", Document.class), EvidenceRecord.Seal.class);
            out.add(new Unsealed(id, head.at(), head.record().latestHash(), head.count(), head.closed(), latest));
        }
        out.sort(Unsealed.NEWEST_FIRST);
        return out;
    }

    @Override
    public EvidenceRecord seal(String jobId, EvidenceRecord.Seal seal) {
        if (seal == null || seal.at() == null) {
            throw new IllegalArgumentException("a seal needs the time it was made");
        }
        if (jobId == null) {
            throw new NotFoundException("evidence record", jobId);
        }
        synchronized (lock(jobId)) {
            Head head = head(jobId);
            if (head == null) {
                throw new NotFoundException("evidence record", jobId);
            }
            if (head.seal() == null) {
                if (head.behind()) {
                    grow(jobId, head);
                }
                // written once: the filter matches only a record without a seal, so a second seal changes nothing
                mongo.collection(RECORDS).updateOne(Filters.and(Filters.eq("_id", jobId), Filters.exists("seal", false)),
                        Updates.set("seal", mongo.doc(seal)));
            }
            return record(jobId).orElseThrow(() -> new NotFoundException("evidence record", jobId));
        }
    }

    /** Kept once per head: a second signature of a head already checkpointed is refused by the key and the first stands. */
    @Override
    public EvidenceRecord checkpoint(String jobId, EvidenceRecord.Seal checkpoint) {
        if (checkpoint == null || checkpoint.at() == null) {
            throw new IllegalArgumentException("a checkpoint needs the time it was made");
        }
        if (jobId == null) {
            throw new NotFoundException("evidence record", jobId);
        }
        synchronized (lock(jobId)) {
            Head head = head(jobId);
            if (head == null) {
                throw new NotFoundException("evidence record", jobId);
            }
            int signed = checkpoint.seq() == null ? head.count() : checkpoint.seq();
            try {
                mongo.collection(CHECKPOINTS).insertOne(new Document("_id", key(jobId, signed)).append(Mongo.DOC, mongo.doc(checkpoint)));
            } catch (MongoException e) {
                if (!Mongo.duplicateKey(e)) {
                    throw e;
                }
                // this head is checkpointed already: the first signature stands
            }
            return record(jobId).orElseThrow(() -> new NotFoundException("evidence record", jobId));
        }
    }

    @Override
    public ShareToken shareToken(String jobId, Instant expiresAt) {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        ShareToken token = new ShareToken(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), jobId, expiresAt);
        mongo.collection(SHARE_LINKS).insertOne(new Document("_id", Hashes.sha256(token.token()))
                .append(Mongo.DOC, mongo.doc(new ShareToken(null, jobId, expiresAt))));
        return token;
    }

    @Override
    public Optional<ShareToken> findShareToken(String token) {
        if (token == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(mongo.collection(SHARE_LINKS).find(Filters.eq("_id", Hashes.sha256(token))).first())
                .map(d -> mongo.read(d, ShareToken.class))
                .map(link -> new ShareToken(token, link.jobId(), link.expiresAt()));
    }

    // ------------------------------------------------------------------ the header

    /**
     * A record without its entries: the projection over entries 1..{@code count} (seal and
     * checkpoints apart), when the first was written, whether a {@code closed} one is among them,
     * its seal, and how many entries its header document counts ({@code stored}, 0 without one).
     */
    private record Head(EvidenceRecord record, Instant at, int count, boolean closed, EvidenceRecord.Seal seal, int stored) {

        static Head of(Mongo mongo, Document header) {
            int count = header.getInteger("count");
            Document seal = header.get("seal", Document.class);
            return new Head(mongo.read(header, EvidenceRecord.class), instant(header.getLong("requestedNs")), count,
                    header.getBoolean("closed", false), seal == null ? null : mongo.as(seal, EvidenceRecord.Seal.class), count);
        }

        static Head open(String jobId, EvidenceEntry first) {
            return new Head(bare(EvidenceRecord.start(jobId, first)), first.at(), first.seq(), first.kind() == EvidenceKind.CLOSED, null, 0);
        }

        Head then(EvidenceEntry e) {
            return new Head(bare(record.append(e)), at, e.seq(), closed || e.kind() == EvidenceKind.CLOSED, seal, stored);
        }

        /** True when the header document has not counted every entry folded in here. */
        boolean behind() {
            return stored < count;
        }

        /** What the header's doc keeps: the record without its entries, seal and checkpoints, which have their own fields and documents. */
        static EvidenceRecord bare(EvidenceRecord r) {
            return new EvidenceRecord(r.jobId(), r.workflow(), r.version(), r.requester(), r.channel(), List.of(), r.latestHash(), null,
                    r.retention(), r.change(), List.of());
        }
    }

    /** The record's header with the entries it has not counted yet folded in; null when the record has no entry. */
    private Head head(String id) {
        Document header = mongo.collection(RECORDS).find(Filters.eq("_id", id)).first();
        Head head = header == null ? null : Head.of(mongo, header);
        int counted = head == null ? 0 : head.count();
        for (Document d : mongo.collection(ENTRIES).find(Filters.and(Filters.eq("_id.jobId", id), Filters.gt("_id.seq", counted)))
                .sort(Sorts.ascending("_id.seq"))) {
            EvidenceEntry e = mongo.read(d, EvidenceEntry.class);
            head = head == null ? Head.open(id, e) : head.then(e);
        }
        return head;
    }

    /**
     * Moves the header forward to {@code head}, creating it with the record's first entry. The
     * update only matches a header that has counted fewer entries, and every writer folds the same
     * entries to the same header, so writers racing here, or a header already further on, leave
     * it right. Seal and checkpoints are not touched.
     */
    private void grow(String id, Head head) {
        Bson behind = Filters.and(Filters.eq("_id", id), Filters.lt("count", head.count()));
        EvidenceRecord r = head.record();
        Bson update = Updates.combine(
                Updates.set("workflow", r.workflow()),
                Updates.set("requester", r.requester()),
                Updates.set("requestedNs", Mongo.nanos(head.at())),
                Updates.set("count", head.count()),
                Updates.set("closed", head.closed()),
                Updates.set(Mongo.DOC, mongo.doc(r)));
        try {
            mongo.collection(RECORDS).updateOne(behind, update, new UpdateOptions().upsert(true));
        } catch (MongoException e) {
            if (!Mongo.duplicateKey(e)) {
                throw e;
            }
            // the header exists: further on already (then this matches nothing), or just created by a writer behind this one
            mongo.collection(RECORDS).updateOne(behind, update);
        }
    }

    /** The record from its header and every entry read after it, the entries the header has not counted folded in. */
    private EvidenceRecord build(String id, Document header, List<EvidenceEntry> entries, List<EvidenceRecord.Seal> checkpoints) {
        if (header == null && entries.isEmpty()) {
            return null;
        }
        Head head = header == null ? null : Head.of(mongo, header);
        int counted = head == null ? 0 : head.count();
        for (EvidenceEntry e : entries) {
            if (e.seq() > counted) {
                head = head == null ? Head.open(id, e) : head.then(e);
            }
        }
        EvidenceRecord r = head.record();
        return new EvidenceRecord(r.jobId(), r.workflow(), r.version(), r.requester(), r.channel(), List.copyOf(entries), r.latestHash(),
                head.seal(), r.retention(), r.change(), checkpoints);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The entry as it will read back from its document, so the hash chained now is the hash
     * verified later: its data as the mapping returns it, and its text, actor included, as
     * {@link Mongo#doc} stores it (half a surrogate pair as U+FFFD, which is what the stored text
     * would read back as).
     */
    private EvidenceEntry stable(EvidenceEntry e) {
        return mongo.as(mongo.doc(e), EvidenceEntry.class);
    }

    /** The documents {@code which} picks, in one query, by record, each record's in {@code seq} order. */
    private <T> Map<String, List<T>> byRecord(String collection, Bson which, Function<Document, T> read) {
        Map<String, List<T>> out = new LinkedHashMap<>();
        mongo.collection(collection).find(which).sort(Sorts.ascending("_id.jobId", "_id.seq"))
                .forEach(d -> out.computeIfAbsent(d.get("_id", Document.class).getString("jobId"), k -> new ArrayList<>()).add(read.apply(d)));
        return out;
    }

    /** The highest-{@code seq} document of each of these records, as {@code accumulator} takes it, walking the {@code {jobId, seq}} index. */
    private Map<String, Document> last(String collection, List<String> ids, BsonField accumulator) {
        Map<String, Document> out = new HashMap<>();
        mongo.collection(collection).aggregate(List.of(
                Aggregates.match(Filters.in("_id.jobId", ids)),
                Aggregates.sort(Sorts.descending("_id.jobId", "_id.seq")),
                Aggregates.group("$_id.jobId", accumulator)))
                .forEach(d -> out.put(d.getString("_id"), d));
        return out;
    }

    private static Document key(String id, int seq) {
        return new Document("jobId", id).append("seq", seq);
    }

    private static Instant instant(Long ns) {
        return ns == null ? null : Instant.ofEpochSecond(Math.floorDiv(ns, 1_000_000_000L), Math.floorMod(ns, 1_000_000_000L));
    }

    private static String requireId(String id) {
        if (id == null) {
            throw new IllegalArgumentException("an evidence record needs an id");
        }
        return id;
    }

    /** The record's lock stripe: appends, seals and checkpoints of one record take turns in this instance. */
    private Object lock(String id) {
        return stripes[Math.floorMod(id.hashCode(), stripes.length)];
    }
}
