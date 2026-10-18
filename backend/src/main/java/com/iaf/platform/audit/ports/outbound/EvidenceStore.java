package com.iaf.platform.audit.ports.outbound;

import java.time.Instant;
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
     * Chains {@code entry} onto the job's record (assigning {@code seq}, {@code previous} and
     * {@code hash}) and keeps it; the first entry opens the record. A sealed record is final and
     * refuses the append with an {@link IllegalStateException}; checkpoints do not close a record.
     */
    EvidenceEntry append(String jobId, EvidenceEntry entry);

    Optional<EvidenceRecord> record(String jobId);

    /** Matching records, newest first. */
    List<EvidenceRecord> records(EvidenceFilter filter);

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
