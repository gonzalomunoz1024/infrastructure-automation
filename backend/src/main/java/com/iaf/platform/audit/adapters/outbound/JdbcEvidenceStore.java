package com.iaf.platform.audit.adapters.outbound;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.platform.audit.domain.EvidenceEntry;
import com.iaf.platform.audit.domain.EvidenceFilter;
import com.iaf.platform.audit.domain.EvidenceRecord;
import com.iaf.platform.audit.domain.HashChain;
import com.iaf.platform.audit.domain.ShareToken;
import com.iaf.platform.audit.ports.outbound.EvidenceStore;
import com.iaf.platform.common.adapters.jdbc.Jdbc;
import com.iaf.platform.common.domain.NotFoundException;

/**
 * The evidence store of the {@code postgres} profile, unless a write-once store ({@code worm},
 * {@code worm-s3}) is active. A job's entries are rows of {@code evidence_entries}, only ever inserted; its
 * {@code evidence_records} row is the header (the record without its entries), which an append
 * grows and a seal fixes once. Appends to one record are serialised by locking its header, so
 * concurrent writers still make one chain. The PostgreSQL migration backs this with triggers: the
 * database itself refuses to change or delete an entry, to add one to a sealed record, or to
 * change a sealed header. A share link is kept as the SHA-256 of its token, never the token.
 */
@Component
@Primary
@Profile("postgres & !worm & !worm-s3")
public class JdbcEvidenceStore implements EvidenceStore {

    private static final Comparator<EvidenceRecord> NEWEST_FIRST = Comparator
            .comparing((EvidenceRecord r) -> r.at() == null ? Instant.EPOCH : r.at()).reversed()
            .thenComparing(EvidenceRecord::jobId);

    private static final String WITH_ENTRIES = "SELECT r.job_id, r.doc, e.doc FROM evidence_records r"
            + " LEFT JOIN evidence_entries e ON e.job_id = r.job_id";

    private final Jdbc jdbc;
    private final SecureRandom random = new SecureRandom();

    public JdbcEvidenceStore(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public EvidenceEntry append(String jobId, EvidenceEntry entry) {
        if (entry.kind() == null) {
            throw new IllegalArgumentException("an evidence entry needs its kind");
        }
        return jdbc.inTransaction(() -> {
            Optional<Header> locked = lock(jobId);
            if (locked.isEmpty()) {
                EvidenceEntry first = entry.chained(1, HashChain.GENESIS);
                EvidenceRecord opened = EvidenceRecord.start(jobId, first);
                if (jdbc.insertIfAbsent("INSERT INTO evidence_records (job_id, workflow, requester, requested_at, requested_ns, entry_count, latest_hash, doc)"
                        + " VALUES (?, ?, ?, ?, ?, 1, ?, ?) ON CONFLICT DO NOTHING",
                        jobId, opened.workflow(), opened.requester(), Jdbc.time(first.at()), Jdbc.nanos(first.at()), first.hash(), header(opened))) {
                    insert(jobId, first);
                    return first;
                }
                // a concurrent append opened the record first: chain after its entry
                locked = lock(jobId);
            }
            Header current = locked.orElseThrow(() -> new IllegalStateException("evidence record " + jobId + " vanished while appending"));
            if (current.record().sealed() != null) {
                throw new IllegalStateException("evidence record " + jobId + " is sealed; a late " + entry.kind().json() + " entry is not accepted");
            }
            EvidenceEntry chained = entry.chained(current.count() + 1, current.record().latestHash());
            EvidenceRecord grown = current.record().append(chained);
            jdbc.update("UPDATE evidence_records SET workflow = ?, requester = ?, entry_count = ?, latest_hash = ?, doc = ? WHERE job_id = ?",
                    grown.workflow(), grown.requester(), chained.seq(), chained.hash(), header(grown), jobId);
            insert(jobId, chained);
            return chained;
        });
    }

    @Override
    public Optional<EvidenceRecord> record(String jobId) {
        if (jobId == null) {
            return Optional.empty();
        }
        return withEntries(WITH_ENTRIES + " WHERE r.job_id = ? ORDER BY e.seq", jobId).stream().findFirst();
    }

    @Override
    public List<EvidenceRecord> records(EvidenceFilter filter) {
        EvidenceFilter f = filter == null ? EvidenceFilter.ALL : filter;
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        if (f.workflow() != null) {
            where.append(" AND r.workflow = ?");
            args.add(f.workflow());
        }
        if (f.requester() != null) {
            where.append(" AND r.requester = ?");
            args.add(f.requester());
        }
        if (f.from() != null) {
            where.append(" AND r.requested_ns >= ?");
            args.add(Jdbc.nanos(f.from()));
        }
        if (f.to() != null) {
            where.append(" AND r.requested_ns < ?");
            args.add(Jdbc.nanos(f.to()));
        }
        String sql = WITH_ENTRIES + (where.isEmpty() ? "" : " WHERE" + where.substring(4)) + " ORDER BY r.job_id, e.seq";
        return withEntries(sql, args.toArray()).stream().filter(f::matches).sorted(NEWEST_FIRST).toList();
    }

    @Override
    public EvidenceRecord seal(String jobId, EvidenceRecord.Seal seal) {
        if (seal == null || seal.at() == null) {
            throw new IllegalArgumentException("a seal needs the time it was made");
        }
        return jdbc.inTransaction(() -> {
            Header current = lock(jobId).orElseThrow(() -> new NotFoundException("evidence record", jobId));
            if (current.record().sealed() == null) {
                jdbc.update("UPDATE evidence_records SET sealed_at = ?, doc = ? WHERE job_id = ? AND sealed_at IS NULL",
                        Jdbc.time(seal.at()), header(current.record().withSeal(seal)), jobId);
            }
            return record(jobId).orElseThrow(() -> new NotFoundException("evidence record", jobId));
        });
    }

    @Override
    public EvidenceRecord checkpoint(String jobId, EvidenceRecord.Seal checkpoint) {
        if (checkpoint == null || checkpoint.at() == null) {
            throw new IllegalArgumentException("a checkpoint needs the time it was made");
        }
        return jdbc.inTransaction(() -> {
            Header current = lock(jobId).orElseThrow(() -> new NotFoundException("evidence record", jobId));
            EvidenceRecord updated = current.record().withCheckpoint(checkpoint);
            if (updated != current.record()) {
                jdbc.update("UPDATE evidence_records SET doc = ? WHERE job_id = ?", header(updated), jobId);
            }
            return record(jobId).orElseThrow(() -> new NotFoundException("evidence record", jobId));
        });
    }

    @Override
    public ShareToken shareToken(String jobId, Instant expiresAt) {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        ShareToken token = new ShareToken(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), jobId, expiresAt);
        jdbc.update("INSERT INTO evidence_share_links (token_hash, job_id, expires_at, doc) VALUES (?, ?, ?, ?)",
                Jdbc.sha256(token.token()), jobId, Jdbc.time(expiresAt), jdbc.json(new ShareToken(null, jobId, expiresAt)));
        return token;
    }

    @Override
    public Optional<ShareToken> findShareToken(String token) {
        if (token == null) {
            return Optional.empty();
        }
        return jdbc.first("SELECT doc FROM evidence_share_links WHERE token_hash = ?",
                (rs, i) -> jdbc.read(rs.getString(1), ShareToken.class), Jdbc.sha256(token))
                .map(link -> new ShareToken(token, link.jobId(), link.expiresAt()));
    }

    /** The header and its entry count, locked until the transaction ends. */
    private Optional<Header> lock(String jobId) {
        return jdbc.first("SELECT doc, entry_count FROM evidence_records WHERE job_id = ? FOR UPDATE",
                (rs, i) -> new Header(jdbc.read(rs.getString(1), EvidenceRecord.class), rs.getInt(2)), jobId);
    }

    private void insert(String jobId, EvidenceEntry entry) {
        jdbc.update("INSERT INTO evidence_entries (job_id, seq, at, kind, hash, doc) VALUES (?, ?, ?, ?, ?, ?)",
                jobId, entry.seq(), Jdbc.time(entry.at()), entry.kind().json(), entry.hash(), jdbc.json(entry));
    }

    /** Headers joined with their entries in one statement, so a record never mixes two moments of an append. */
    private List<EvidenceRecord> withEntries(String sql, Object... args) {
        Map<String, String> headers = new LinkedHashMap<>();
        Map<String, List<EvidenceEntry>> entries = new LinkedHashMap<>();
        for (Row row : jdbc.query(sql, (rs, i) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3)), args)) {
            headers.putIfAbsent(row.jobId(), row.header());
            List<EvidenceEntry> list = entries.computeIfAbsent(row.jobId(), id -> new ArrayList<>());
            if (row.entry() != null) {
                list.add(jdbc.read(row.entry(), EvidenceEntry.class));
            }
        }
        List<EvidenceRecord> records = new ArrayList<>(headers.size());
        headers.forEach((jobId, doc) -> {
            EvidenceRecord h = jdbc.read(doc, EvidenceRecord.class);
            records.add(new EvidenceRecord(h.jobId(), h.workflow(), h.version(), h.requester(), h.channel(), List.copyOf(entries.get(jobId)),
                    h.latestHash(), h.sealed(), h.retention(), h.change(), h.checkpoints()));
        });
        return records;
    }

    /** The record without its entries (the entries have their own rows): what the header row's doc keeps, checkpoints included. */
    private String header(EvidenceRecord r) {
        return jdbc.json(new EvidenceRecord(r.jobId(), r.workflow(), r.version(), r.requester(), r.channel(), List.of(), r.latestHash(),
                r.sealed(), r.retention(), r.change(), r.checkpoints()));
    }

    private record Header(EvidenceRecord record, int count) {
    }

    private record Row(String jobId, String header, String entry) {
    }
}
