package com.iaf.platform.audit.ports.outbound;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.iaf.platform.audit.domain.EvidenceEntry;
import com.iaf.platform.audit.domain.EvidenceFilter;
import com.iaf.platform.audit.domain.EvidenceRecord;
import com.iaf.platform.audit.domain.ShareToken;

/**
 * Where evidence lives. Append-only by design: entries are chained as they arrive, a record is
 * sealed once, and nothing is ever updated or deleted. The demo adapter keeps records in
 * memory; production writes each entry as a write-once object (see {@code WormEvidenceStore}).
 */
public interface EvidenceStore {

    /**
     * Chains {@code entry} onto the job's record (assigning {@code seq}, {@code previous} and
     * {@code hash}) and keeps it; the first entry opens the record. A sealed record is final and
     * refuses the append with an {@link IllegalStateException}.
     */
    EvidenceEntry append(String jobId, EvidenceEntry entry);

    Optional<EvidenceRecord> record(String jobId);

    /** Matching records, newest first. */
    List<EvidenceRecord> records(EvidenceFilter filter);

    /** Write-once: a record that is already sealed keeps its first seal. 404 for an unknown job. */
    EvidenceRecord seal(String jobId, EvidenceRecord.Seal seal);

    /** Mints and keeps a read-only link to one record. */
    ShareToken shareToken(String jobId, Instant expiresAt);

    Optional<ShareToken> findShareToken(String token);
}
