package com.iaf.platform.workflows.adapters.outbound;

import java.util.List;
import java.util.Optional;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.platform.common.adapters.jdbc.Jdbc;
import com.iaf.platform.workflows.domain.ChangeRequest;
import com.iaf.platform.workflows.ports.outbound.ChangeRequestPort;

/** The change requests of the {@code postgres} profile: one row per change request in {@code change_requests}, newest first. */
@Component
@Primary
@Profile("postgres")
public class JdbcChangeRequests implements ChangeRequestPort {

    private final Jdbc jdbc;

    public JdbcChangeRequests(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ChangeRequest> all() {
        return jdbc.query("SELECT doc FROM change_requests ORDER BY created_ns DESC NULLS LAST, id", (rs, i) -> read(rs.getString(1)));
    }

    @Override
    public Optional<ChangeRequest> get(String id) {
        return id == null ? Optional.empty() : jdbc.first("SELECT doc FROM change_requests WHERE id = ?", (rs, i) -> read(rs.getString(1)), id);
    }

    @Override
    public void save(ChangeRequest cr) {
        String status = cr.status() == null ? null : cr.status().json();
        String doc = jdbc.json(cr);
        jdbc.upsert("UPDATE change_requests SET name = ?, status = ?, created_at = ?, created_ns = ?, doc = ? WHERE id = ?",
                new Object[] {cr.name(), status, Jdbc.time(cr.createdAt()), Jdbc.nanos(cr.createdAt()), doc, cr.id()},
                "INSERT INTO change_requests (id, name, status, created_at, created_ns, doc) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                new Object[] {cr.id(), cr.name(), status, Jdbc.time(cr.createdAt()), Jdbc.nanos(cr.createdAt()), doc});
    }

    private ChangeRequest read(String doc) {
        return jdbc.read(doc, ChangeRequest.class);
    }
}
