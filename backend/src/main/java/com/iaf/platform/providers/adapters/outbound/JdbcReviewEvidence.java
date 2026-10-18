package com.iaf.platform.providers.adapters.outbound;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.platform.common.adapters.jdbc.Jdbc;
import com.iaf.platform.providers.domain.ReviewEvidence;
import com.iaf.platform.providers.domain.ReviewEvidence.ActionTry;
import com.iaf.platform.providers.domain.ReviewEvidence.ConnectionTest;
import com.iaf.platform.providers.ports.outbound.ReviewEvidencePort;

/**
 * The review evidence of the {@code postgres} profile: one row of {@code review_evidence} per
 * connection test or Try it call, by provider name, so what the wizard proved survives a restart
 * and every instance sees it at once. The bounds are the in-memory store's: the last
 * {@value InMemoryReviewEvidence#MAX_CONNECTIONS} connection tests and the last
 * {@value InMemoryReviewEvidence#MAX_TRIES} tries of a provider (a write deletes the oldest
 * beyond them and adds them to the provider's count of dropped entries, a row of kind
 * {@value #DROPPED_CONNECTIONS} or {@value #DROPPED_TRIES} whose doc is the number), and a response
 * over {@value InMemoryReviewEvidence#MAX_RESPONSE_BYTES} bytes is kept without its body. A row
 * holds endpoints, credential references and responses, never a credential value.
 */
@Component
@Primary
@Profile("postgres")
public class JdbcReviewEvidence implements ReviewEvidencePort {

    static final String CONNECTION = "connection";
    static final String TRY = "try";
    static final String DROPPED_CONNECTIONS = "dropped-connection";
    static final String DROPPED_TRIES = "dropped-try";

    private static final String SELECT = "SELECT doc FROM review_evidence WHERE provider = ? AND kind = ? ORDER BY id";

    private final Jdbc jdbc;

    public JdbcReviewEvidence(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void recordConnection(String provider, ConnectionTest test) {
        if (provider == null || test == null) {
            return;
        }
        record(provider, CONNECTION, DROPPED_CONNECTIONS, test.at(), jdbc.json(test), InMemoryReviewEvidence.MAX_CONNECTIONS);
    }

    @Override
    public void recordTry(String provider, ActionTry tryResult) {
        if (provider == null || tryResult == null) {
            return;
        }
        ActionTry kept = InMemoryReviewEvidence.bounded(tryResult);
        record(provider, TRY, DROPPED_TRIES, kept.at(), jdbc.json(kept), InMemoryReviewEvidence.MAX_TRIES);
    }

    @Override
    public ReviewEvidence evidence(String provider) {
        if (provider == null) {
            return ReviewEvidence.NONE;
        }
        List<ConnectionTest> connections = jdbc.query(SELECT, (rs, i) -> jdbc.read(rs.getString(1), ConnectionTest.class), provider, CONNECTION);
        List<ActionTry> tries = jdbc.query(SELECT, (rs, i) -> jdbc.read(rs.getString(1), ActionTry.class), provider, TRY);
        return new ReviewEvidence(connections, tries, dropped(provider, DROPPED_CONNECTIONS), dropped(provider, DROPPED_TRIES));
    }

    /**
     * Appends an entry and deletes the provider's oldest entries of that kind beyond {@code keep},
     * adding how many went to its count of dropped entries, in one transaction.
     */
    private void record(String provider, String kind, String droppedKind, Instant at, String doc, int keep) {
        jdbc.inTransaction(() -> {
            jdbc.update("INSERT INTO review_evidence (provider, kind, at, doc) VALUES (?, ?, ?, ?)", provider, kind, Jdbc.time(at), doc);
            // the oldest entry to keep is the keep-th newest; everything before it goes
            jdbc.first("SELECT id FROM review_evidence WHERE provider = ? AND kind = ? ORDER BY id DESC LIMIT 1 OFFSET " + (keep - 1),
                    (rs, i) -> rs.getLong(1), provider, kind)
                    .ifPresent(oldest -> {
                        int gone = jdbc.update("DELETE FROM review_evidence WHERE provider = ? AND kind = ? AND id < ?", provider, kind, oldest);
                        if (gone > 0) {
                            countDropped(provider, droppedKind, gone, at);
                        }
                    });
        });
    }

    /**
     * Adds {@code gone} to the count row of a provider and kind, created on the first drop. The
     * first row is locked while it changes, so two writers never lose a count; should two of them
     * create the row at once, the count is the sum of the rows.
     */
    private void countDropped(String provider, String kind, int gone, Instant at) {
        Optional<long[]> row = jdbc.first("SELECT id, doc FROM review_evidence WHERE provider = ? AND kind = ? ORDER BY id LIMIT 1 FOR UPDATE",
                (rs, i) -> new long[] {rs.getLong(1), count(rs.getString(2))}, provider, kind);
        if (row.isPresent()) {
            jdbc.update("UPDATE review_evidence SET doc = ? WHERE id = ?", String.valueOf(row.get()[1] + gone), row.get()[0]);
        } else {
            jdbc.update("INSERT INTO review_evidence (provider, kind, at, doc) VALUES (?, ?, ?, ?)", provider, kind, Jdbc.time(at), String.valueOf(gone));
        }
    }

    private int dropped(String provider, String kind) {
        long sum = jdbc.query("SELECT doc FROM review_evidence WHERE provider = ? AND kind = ?", (rs, i) -> count(rs.getString(1)), provider, kind)
                .stream().mapToLong(Long::longValue).sum();
        return (int) Math.min(Integer.MAX_VALUE, sum);
    }

    private static long count(String doc) {
        try {
            return doc == null ? 0 : Math.max(0, Long.parseLong(doc.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
