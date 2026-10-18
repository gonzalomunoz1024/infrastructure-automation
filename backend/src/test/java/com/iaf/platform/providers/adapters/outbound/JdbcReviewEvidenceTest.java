package com.iaf.platform.providers.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.iaf.platform.common.adapters.jdbc.Jdbc;
import com.iaf.platform.common.adapters.jdbc.TestDatabase;
import com.iaf.platform.providers.ports.outbound.ReviewEvidencePort;

class JdbcReviewEvidenceTest {

    abstract static class OnDatabase extends ReviewEvidenceContract {

        abstract TestDatabase db();

        @Override
        ReviewEvidencePort newStore() {
            return new JdbcReviewEvidence(db().jdbc());
        }

        @Override
        ReviewEvidencePort reopen() {
            return new JdbcReviewEvidence(new Jdbc(db().dataSource()));
        }

        @Test
        void theBoundDeletesTheOldestRowsInsteadOfHidingThem() {
            for (int i = 0; i < InMemoryReviewEvidence.MAX_CONNECTIONS + 5; i++) {
                store.recordConnection("fleet", connection("dev", true, i, T0.plusSeconds(i)));
            }
            store.recordConnection("vault", connection("dev", true, 1, T0));
            assertThat(rows("fleet", JdbcReviewEvidence.CONNECTION)).isEqualTo(InMemoryReviewEvidence.MAX_CONNECTIONS);
            assertThat(rows("vault", JdbcReviewEvidence.CONNECTION)).isEqualTo(1);
            assertThat(rows("fleet", JdbcReviewEvidence.TRY)).isZero();
        }

        @Test
        void anotherInstanceSeesEvidenceAtOnce() {
            ReviewEvidencePort other = reopen();
            store.recordTry("fleet", answered("vm.get", 80, T0));
            assertThat(other.evidence("fleet").tries()).containsExactly(answered("vm.get", 80, T0));
        }

        private long rows(String provider, String kind) {
            return db().jdbc().first("SELECT COUNT(*) FROM review_evidence WHERE provider = ? AND kind = ?", (rs, i) -> rs.getLong(1), provider, kind).orElse(0L);
        }
    }

    @Nested
    class OnH2 extends OnDatabase {
        @RegisterExtension
        final TestDatabase db = TestDatabase.h2();

        @Override
        TestDatabase db() {
            return db;
        }
    }

    @Nested
    @EnabledIfEnvironmentVariable(named = TestDatabase.POSTGRES_URL, matches = ".+")
    class OnPostgres extends OnDatabase {
        @RegisterExtension
        final TestDatabase db = TestDatabase.postgres();

        @Override
        TestDatabase db() {
            return db;
        }
    }
}
