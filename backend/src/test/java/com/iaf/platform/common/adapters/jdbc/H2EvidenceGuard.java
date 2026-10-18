package com.iaf.platform.common.adapters.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.h2.api.Trigger;

/**
 * The append-only rules of {@code db/migration/postgresql/V1_1__evidence_append_only.sql} for the
 * H2 test databases (src/test/resources/db/migration/h2), so the adapter tests see the database
 * refuse what the PostgreSQL triggers refuse: changing or deleting an entry, adding one to a
 * sealed record, deleting a header, changing a sealed header.
 */
public class H2EvidenceGuard implements Trigger {

    /** SQLSTATE check_violation, as the PostgreSQL triggers raise. */
    private static final String CHECK_VIOLATION = "23514";
    /** evidence_records columns in table order. */
    private static final int RECORD_JOB_ID = 0;
    private static final int RECORD_REQUESTED_NS = 4;
    private static final int RECORD_ENTRY_COUNT = 5;
    private static final int RECORD_SEALED_AT = 7;

    private boolean entries;

    @Override
    public void init(Connection conn, String schemaName, String triggerName, String tableName, boolean before, int type) {
        entries = "evidence_entries".equalsIgnoreCase(tableName);
    }

    @Override
    public void fire(Connection conn, Object[] oldRow, Object[] newRow) throws SQLException {
        if (entries) {
            if (oldRow != null) {
                throw new SQLException("evidence entries are append-only", CHECK_VIOLATION);
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM evidence_records WHERE job_id = ? AND sealed_at IS NOT NULL")) {
                ps.setObject(1, newRow[0]);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        throw new SQLException("evidence record " + newRow[0] + " is sealed; no more entries are accepted", CHECK_VIOLATION);
                    }
                }
            }
            return;
        }
        if (newRow == null) {
            throw new SQLException("evidence records are never deleted", CHECK_VIOLATION);
        }
        if (oldRow[RECORD_SEALED_AT] != null) {
            throw new SQLException("evidence record " + oldRow[RECORD_JOB_ID] + " is sealed and cannot change", CHECK_VIOLATION);
        }
        boolean shrinks = ((Number) newRow[RECORD_ENTRY_COUNT]).intValue() < ((Number) oldRow[RECORD_ENTRY_COUNT]).intValue();
        boolean moved = !String.valueOf(newRow[RECORD_JOB_ID]).equals(String.valueOf(oldRow[RECORD_JOB_ID]))
                || !java.util.Objects.equals(newRow[RECORD_REQUESTED_NS], oldRow[RECORD_REQUESTED_NS]);
        if (shrinks || moved) {
            throw new SQLException("evidence record " + oldRow[RECORD_JOB_ID] + " can only grow", CHECK_VIOLATION);
        }
    }
}
