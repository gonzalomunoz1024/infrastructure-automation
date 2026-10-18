-- The H2 twin of db/migration/postgresql/V1_1__evidence_append_only.sql, for the adapter tests:
-- the same rules as a Java trigger (H2 has no PL/pgSQL). Only tests run on H2.
CREATE TRIGGER evidence_entries_append_only BEFORE INSERT, UPDATE, DELETE ON evidence_entries
    FOR EACH ROW CALL 'com.iaf.platform.common.adapters.jdbc.H2EvidenceGuard';

CREATE TRIGGER evidence_records_guard BEFORE UPDATE, DELETE ON evidence_records
    FOR EACH ROW CALL 'com.iaf.platform.common.adapters.jdbc.H2EvidenceGuard';
