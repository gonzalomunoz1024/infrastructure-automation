package com.iaf.platform.providers.adapters.outbound;

import java.time.Instant;
import java.util.List;

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
 * beyond them), and a response over {@value InMemoryReviewEvidence#MAX_RESPONSE_BYTES} bytes is
 * kept without its body. A row holds endpoints, credential references and responses, never a
 * credential value.
 */
@Component
@Primary
@Profile("postgres")
public class JdbcReviewEvidence implements ReviewEvidencePort {

    static final String CONNECTION = "connection";
    static final String TRY = "try";

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
        record(provider, CONNECTION, test.at(), jdbc.json(test), InMemoryReviewEvidence.MAX_CONNECTIONS);
    }

    @Override
    public void recordTry(String provider, ActionTry tryResult) {
        if (provider == null || tryResult == null) {
            return;
        }
        ActionTry kept = InMemoryReviewEvidence.bounded(tryResult);
        record(provider, TRY, kept.at(), jdbc.json(kept), InMemoryReviewEvidence.MAX_TRIES);
    }

    @Override
    public ReviewEvidence evidence(String provider) {
        if (provider == null) {
            return ReviewEvidence.NONE;
        }
        List<ConnectionTest> connections = jdbc.query(SELECT, (rs, i) -> jdbc.read(rs.getString(1), ConnectionTest.class), provider, CONNECTION);
        List<ActionTry> tries = jdbc.query(SELECT, (rs, i) -> jdbc.read(rs.getString(1), ActionTry.class), provider, TRY);
        return new ReviewEvidence(connections, tries);
    }

    /** Appends an entry and deletes the provider's oldest entries of that kind beyond {@code keep}, in one transaction. */
    private void record(String provider, String kind, Instant at, String doc, int keep) {
        jdbc.inTransaction(() -> {
            jdbc.update("INSERT INTO review_evidence (provider, kind, at, doc) VALUES (?, ?, ?, ?)", provider, kind, Jdbc.time(at), doc);
            // the oldest entry to keep is the keep-th newest; everything before it goes
            jdbc.first("SELECT id FROM review_evidence WHERE provider = ? AND kind = ? ORDER BY id DESC LIMIT 1 OFFSET " + (keep - 1),
                    (rs, i) -> rs.getLong(1), provider, kind)
                    .ifPresent(oldest -> jdbc.update("DELETE FROM review_evidence WHERE provider = ? AND kind = ? AND id < ?", provider, kind, oldest));
        });
    }
}
