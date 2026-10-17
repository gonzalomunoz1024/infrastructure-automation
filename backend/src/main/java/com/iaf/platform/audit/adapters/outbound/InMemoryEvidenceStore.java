package com.iaf.platform.audit.adapters.outbound;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.iaf.platform.audit.domain.EvidenceEntry;
import com.iaf.platform.audit.domain.EvidenceFilter;
import com.iaf.platform.audit.domain.EvidenceRecord;
import com.iaf.platform.audit.domain.HashChain;
import com.iaf.platform.audit.domain.ShareToken;
import com.iaf.platform.audit.ports.outbound.EvidenceStore;
import com.iaf.platform.common.domain.NotFoundException;

/** The demo store: records in a map, chained under a per-record atomic update. */
@Component
public class InMemoryEvidenceStore implements EvidenceStore {

    private static final Comparator<EvidenceRecord> NEWEST_FIRST = Comparator
            .comparing((EvidenceRecord r) -> r.at() == null ? Instant.EPOCH : r.at()).reversed()
            .thenComparing(EvidenceRecord::jobId);

    private final Map<String, EvidenceRecord> records = new ConcurrentHashMap<>();
    private final Map<String, ShareToken> tokens = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    @Override
    public EvidenceEntry append(String jobId, EvidenceEntry entry) {
        EvidenceEntry[] chained = new EvidenceEntry[1];
        records.compute(jobId, (id, current) -> {
            if (current != null && current.sealed() != null) {
                throw new IllegalStateException("evidence record " + id + " is sealed; a late " + entry.kind().json() + " entry is not accepted");
            }
            int seq = current == null ? 1 : current.entries().size() + 1;
            String previous = current == null ? HashChain.GENESIS : current.latestHash();
            chained[0] = entry.chained(seq, previous);
            return current == null ? EvidenceRecord.start(id, chained[0]) : current.append(chained[0]);
        });
        return chained[0];
    }

    @Override
    public Optional<EvidenceRecord> record(String jobId) {
        return jobId == null ? Optional.empty() : Optional.ofNullable(records.get(jobId));
    }

    @Override
    public List<EvidenceRecord> records(EvidenceFilter filter) {
        EvidenceFilter f = filter == null ? EvidenceFilter.ALL : filter;
        return records.values().stream().filter(f::matches).sorted(NEWEST_FIRST).toList();
    }

    @Override
    public EvidenceRecord seal(String jobId, EvidenceRecord.Seal seal) {
        EvidenceRecord sealed = records.computeIfPresent(jobId, (id, current) -> current.sealed() != null ? current : current.withSeal(seal));
        if (sealed == null) {
            throw new NotFoundException("evidence record", jobId);
        }
        return sealed;
    }

    @Override
    public ShareToken shareToken(String jobId, Instant expiresAt) {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        ShareToken token = new ShareToken(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), jobId, expiresAt);
        tokens.put(token.token(), token);
        return token;
    }

    @Override
    public Optional<ShareToken> findShareToken(String token) {
        return token == null ? Optional.empty() : Optional.ofNullable(tokens.get(token));
    }
}
