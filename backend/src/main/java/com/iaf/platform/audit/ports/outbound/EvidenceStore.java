package com.iaf.platform.audit.ports.outbound;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.iaf.platform.audit.domain.EvidenceEntry;
import com.iaf.platform.audit.domain.EvidenceFilter;
import com.iaf.platform.audit.domain.EvidenceRecord;
import com.iaf.platform.audit.domain.ShareToken;

/**
 * Where evidence lives. Append-only by design: entries are chained as they arrive, a closed record
 * is sealed once, an open one collects checkpoints, and nothing is ever updated or deleted. The
 * demo adapter keeps records in memory; the {@code worm} and {@code worm-s3} profiles write each
 * entry as a write-once object (a read-only file, an S3 Object Lock object).
 */
public interface EvidenceStore {

    /**
     * What a sealing run needs of a record that still takes signatures: when it was requested (for
     * the order), its head, how many entries it has, whether it is closed, and its newest checkpoint.
     */
    record Unsealed(String jobId, Instant at, String latestHash, int entries, boolean closed, EvidenceRecord.Seal latestCheckpoint) {

        /** Newest first, as {@link #records} lists them. */
        public static final Comparator<Unsealed> NEWEST_FIRST = Comparator
                .comparing((Unsealed u) -> u.at() == null ? Instant.EPOCH : u.at()).reversed()
                .thenComparing(Unsealed::jobId);

        public static Unsealed of(EvidenceRecord r) {
            return new Unsealed(r.jobId(), r.at(), r.latestHash(), r.entries().size(), r.closed(), r.latestCheckpoint());
        }

        public boolean platform() {
            return EvidenceRecord.PLATFORM.equals(jobId);
        }
    }

    /**
     * Chains {@code entry} onto the job's record (assigning {@code seq}, {@code previous} and
     * {@code hash}) and keeps it; the first entry opens the record. A sealed record is final and
     * refuses the append with an {@link IllegalStateException}; checkpoints do not close a record.
     */
    EvidenceEntry append(String jobId, EvidenceEntry entry);

    Optional<EvidenceRecord> record(String jobId);

    /** Matching records, newest first. */
    List<EvidenceRecord> records(EvidenceFilter filter);

    /**
     * The ids of the records {@link #records} answers, in its order, for a reader that takes them one
     * at a time (an evidence pack). The stores answer it without holding every record at once; this
     * default, for a store that cannot, lists the records.
     */
    default List<String> recordIds(EvidenceFilter filter) {
        return records(filter).stream().map(EvidenceRecord::jobId).toList();
    }

    /**
     * The records that are not sealed and have entries, newest first, without their entries: what a
     * sealing run looks at. The stores answer it without reading a sealed record; this default,
     * for a store that cannot, filters every record.
     */
    default List<Unsealed> unsealed() {
        return records(EvidenceFilter.ALL).stream()
                .filter(r -> r.sealed() == null && !r.entries().isEmpty())
                .map(Unsealed::of)
                .sorted(Unsealed.NEWEST_FIRST)
                .toList();
    }

    /** Write-once: a record that is already sealed keeps its first seal. 404 for an unknown job. */
    EvidenceRecord seal(String jobId, EvidenceRecord.Seal seal);

    /**
     * Keeps one more signature over the head of a record that is still open (the hourly seal of a
     * running job or of the platform stream); the record keeps accepting entries. A checkpoint of a
     * head that is already checkpointed is kept once. 404 for an unknown record. A store that keeps
     * no checkpoints refuses with {@link UnsupportedOperationException}, and the sealing run says so.
     */
    default EvidenceRecord checkpoint(String jobId, EvidenceRecord.Seal checkpoint) {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " keeps no checkpoints");
    }

    /** Mints and keeps a read-only link to one record. */
    ShareToken shareToken(String jobId, Instant expiresAt);

    Optional<ShareToken> findShareToken(String token);
}
