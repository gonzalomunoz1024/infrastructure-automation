package com.iaf.platform.audit.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.dao.DataAccessException;

import com.iaf.platform.audit.domain.EvidenceRecord;
import com.iaf.platform.audit.domain.HashChain;
import com.iaf.platform.audit.domain.ShareToken;
import com.iaf.platform.audit.ports.outbound.EvidenceStore;
import com.iaf.platform.common.adapters.jdbc.Jdbc;
import com.iaf.platform.common.adapters.jdbc.TestDatabase;
import com.iaf.platform.common.domain.Hashes;

class JdbcEvidenceStoreTest {

    abstract static class OnDatabase extends EvidenceStoreContract {

        abstract TestDatabase db();

        @Override
        EvidenceStore newStore() {
            return new JdbcEvidenceStore(db().jdbc());
        }

        @Override
        EvidenceStore reopen() {
            return new JdbcEvidenceStore(new Jdbc(db().dataSource()));
        }

        @Test
        void keepsOnlyTheHashOfAShareToken() {
            EvidenceStore store = newStore();
            store.append("job_1", requested(T0, "vm-restart", "ana"));
            ShareToken token = store.shareToken("job_1", T0.plusSeconds(86400));

            List<String> row = db().jdbc().query("SELECT token_hash, job_id, doc FROM evidence_share_links",
                    (rs, i) -> List.of(rs.getString(1), rs.getString(2), rs.getString(3))).get(0);
            assertThat(row.get(0)).isEqualTo(Hashes.sha256(token.token()));
            assertThat(row).noneMatch(column -> column.contains(token.token()));
        }

        @Test
        void theDatabaseRefusesToRewriteStoredEvidence() {
            EvidenceStore store = newStore();
            store.append("job_1", requested(T0, "vm-restart", "ana"));
            store.append("job_1", closed(T0.plusSeconds(5), Instant.parse("2033-10-17T00:00:00Z")));
            Jdbc jdbc = db().jdbc();

            assertThatThrownBy(() -> jdbc.update("UPDATE evidence_entries SET doc = '{}' WHERE job_id = 'job_1' AND seq = 1"))
                    .isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> jdbc.update("DELETE FROM evidence_entries WHERE job_id = 'job_1'"))
                    .isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> jdbc.update("DELETE FROM evidence_records WHERE job_id = 'job_1'"))
                    .isInstanceOf(DataAccessException.class);

            store.seal("job_1", new EvidenceRecord.Seal(T0.plusSeconds(60), "sig-1", "key-1"));
            assertThatThrownBy(() -> jdbc.update("UPDATE evidence_records SET doc = '{}' WHERE job_id = 'job_1'"))
                    .isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> jdbc.update("INSERT INTO evidence_entries (job_id, seq, kind, hash, doc) VALUES ('job_1', 3, 'step', 'forged', '{}')"))
                    .isInstanceOf(DataAccessException.class);

            EvidenceRecord record = store.record("job_1").orElseThrow();
            assertThat(record.entries()).hasSize(2);
            assertThat(HashChain.verify(record.entries())).isEqualTo(-1);
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
