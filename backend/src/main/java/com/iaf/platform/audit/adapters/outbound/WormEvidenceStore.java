package com.iaf.platform.audit.adapters.outbound;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.platform.audit.domain.EvidenceEntry;
import com.iaf.platform.audit.domain.EvidenceFilter;
import com.iaf.platform.audit.domain.EvidenceRecord;
import com.iaf.platform.audit.domain.ShareToken;
import com.iaf.platform.audit.ports.outbound.EvidenceStore;

/**
 * TODO: the production evidence store on a WORM (write once, read many) object store, such as
 * S3 Object Lock in compliance mode or Azure immutable blobs, with a retention policy that
 * matches {@code platform.audit.retention-years}.
 *
 * <p>Intended layout: one object per entry at {@code evidence/<jobId>/<seq>.json}, written with a
 * conditional put (if-none-match) so a concurrent writer can never take the same {@code seq};
 * {@code previous} is the hash of object {@code seq - 1}, read before writing. The seal is its
 * own object {@code evidence/<jobId>/seal.json}, written once. Share tokens go to a short-lived
 * table (they expire in days, the evidence stays for years). {@link EvidenceRecord} is a
 * projection over the objects of one job, rebuilt by listing its prefix.
 *
 * <p>Active with the {@code live} profile; until it is implemented every call fails loudly rather
 * than silently losing evidence.
 */
@Component
@Primary
@Profile("live")
public class WormEvidenceStore implements EvidenceStore {

    private static final String TODO = "WormEvidenceStore is not implemented yet: wire the firm's object store (see the class Javadoc)";

    @Override
    public EvidenceEntry append(String jobId, EvidenceEntry entry) {
        throw new UnsupportedOperationException(TODO);
    }

    @Override
    public Optional<EvidenceRecord> record(String jobId) {
        throw new UnsupportedOperationException(TODO);
    }

    @Override
    public List<EvidenceRecord> records(EvidenceFilter filter) {
        throw new UnsupportedOperationException(TODO);
    }

    @Override
    public EvidenceRecord seal(String jobId, EvidenceRecord.Seal seal) {
        throw new UnsupportedOperationException(TODO);
    }

    @Override
    public ShareToken shareToken(String jobId, Instant expiresAt) {
        throw new UnsupportedOperationException(TODO);
    }

    @Override
    public Optional<ShareToken> findShareToken(String token) {
        throw new UnsupportedOperationException(TODO);
    }
}
