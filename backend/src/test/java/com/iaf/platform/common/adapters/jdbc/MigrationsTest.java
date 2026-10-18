package com.iaf.platform.common.adapters.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;

/** The schema itself: every store's table, the revisions, and the evidence tables refusing to be rewritten. */
class MigrationsTest {

    private static final List<String> TABLES = List.of("store_revisions", "store_seeds", "workflow_versions", "change_requests", "providers",
            "provider_versions", "review_evidence", "releases", "resource_types", "resources", "evidence_records", "evidence_entries",
            "evidence_share_links", "service_accounts", "assistant_sessions", "telemetry_samples", "runs_definitions", "runs_jobs", "runs_approvals",
            "runs_events", "idempotency_keys", "quota_buckets");

    abstract static class Contract {

        abstract TestDatabase db();

        @Test
        void createsATableForEveryStore() {
            for (String table : TABLES) {
                assertThat(db().jdbc().query("SELECT COUNT(*) FROM " + table, (rs, i) -> rs.getLong(1))).as(table).hasSize(1);
            }
        }

        @Test
        void startsTheRevisionsOfTheCachedStoresAtZero() {
            assertThat(db().jdbc().revision("workflows")).isZero();
            assertThat(db().jdbc().revision("providers")).isZero();
            assertThat(db().jdbc().revision("resource_types")).isZero();
        }

        @Test
        void isUpToDateAfterTheFirstRun() {
            assertThat(Migrations.migrate(db().dataSource())).isZero();
        }

        @Test
        void neverLetsAnEvidenceEntryChangeOrGo() {
            Jdbc jdbc = db().jdbc();
            openRecord(jdbc, "job_1");
            assertThatThrownBy(() -> jdbc.update("UPDATE evidence_entries SET hash = 'forged' WHERE job_id = 'job_1'"))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("append-only");
            assertThatThrownBy(() -> jdbc.update("DELETE FROM evidence_entries WHERE job_id = 'job_1'"))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("append-only");
            assertThat(jdbc.query("SELECT hash FROM evidence_entries WHERE job_id = 'job_1'", (rs, i) -> rs.getString(1))).containsExactly("h1");
        }

        @Test
        void neverDeletesARecordAndFreezesItOnceSealed() {
            Jdbc jdbc = db().jdbc();
            openRecord(jdbc, "job_2");
            assertThatThrownBy(() -> jdbc.update("DELETE FROM evidence_records WHERE job_id = 'job_2'"))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("never deleted");
            assertThatThrownBy(() -> jdbc.update("UPDATE evidence_records SET entry_count = 0 WHERE job_id = 'job_2'"))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("only grow");
            jdbc.update("UPDATE evidence_records SET sealed_at = ? WHERE job_id = 'job_2'", Jdbc.time(Instant.parse("2026-10-18T10:00:00Z")));
            assertThatThrownBy(() -> jdbc.update("UPDATE evidence_records SET latest_hash = 'h9' WHERE job_id = 'job_2'"))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("sealed");
            assertThatThrownBy(() -> jdbc.update("INSERT INTO evidence_entries (job_id, seq, kind, hash, doc) VALUES ('job_2', 2, 'step', 'h2', '{}')"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("sealed");
        }

        private static void openRecord(Jdbc jdbc, String jobId) {
            jdbc.update("INSERT INTO evidence_records (job_id, entry_count, latest_hash, requested_ns, doc) VALUES (?, 1, 'h1', 1, '{}')", jobId);
            jdbc.update("INSERT INTO evidence_entries (job_id, seq, kind, hash, doc) VALUES (?, 1, 'requested', 'h1', '{}')", jobId);
        }
    }

    @Nested
    class OnH2 extends Contract {
        @RegisterExtension
        final TestDatabase db = TestDatabase.h2();

        @Override
        TestDatabase db() {
            return db;
        }
    }

    @Nested
    @EnabledIfEnvironmentVariable(named = TestDatabase.POSTGRES_URL, matches = ".+")
    class OnPostgres extends Contract {
        @RegisterExtension
        final TestDatabase db = TestDatabase.postgres();

        @Override
        TestDatabase db() {
            return db;
        }
    }
}
