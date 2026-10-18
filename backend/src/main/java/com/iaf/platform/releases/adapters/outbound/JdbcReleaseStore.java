package com.iaf.platform.releases.adapters.outbound;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.platform.common.adapters.jdbc.Jdbc;
import com.iaf.platform.releases.domain.Release;
import com.iaf.platform.releases.domain.ReleaseStatus;
import com.iaf.platform.releases.ports.outbound.ReleaseStore;

/**
 * The releases of the {@code postgres} profile: one row per release in {@code releases}, newest
 * first (started at, then id), the active ones (canary or paused) found by their status column.
 */
@Component
@Primary
@Profile("postgres")
public class JdbcReleaseStore implements ReleaseStore {

    private static final String NEWEST_FIRST = " ORDER BY started_ns DESC NULLS LAST, id";
    private static final String ACTIVE = Arrays.stream(ReleaseStatus.values()).filter(ReleaseStatus::active)
            .map(s -> "'" + s.json() + "'").collect(Collectors.joining(", ", "status IN (", ")"));

    private final Jdbc jdbc;

    public JdbcReleaseStore(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Release save(Release release) {
        String status = release.status() == null ? null : release.status().json();
        String doc = jdbc.json(release);
        jdbc.upsert("UPDATE releases SET workflow = ?, status = ?, started_at = ?, started_ns = ?, doc = ? WHERE id = ?",
                new Object[] {release.workflow(), status, Jdbc.time(release.startedAt()), Jdbc.nanos(release.startedAt()), doc, release.id()},
                "INSERT INTO releases (id, workflow, status, started_at, started_ns, doc) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                new Object[] {release.id(), release.workflow(), status, Jdbc.time(release.startedAt()), Jdbc.nanos(release.startedAt()), doc});
        return release;
    }

    @Override
    public Optional<Release> find(String id) {
        return id == null ? Optional.empty() : jdbc.first("SELECT doc FROM releases WHERE id = ?", (rs, i) -> read(rs.getString(1)), id);
    }

    @Override
    public List<Release> list(String workflow) {
        if (workflow == null || workflow.isBlank()) {
            return jdbc.query("SELECT doc FROM releases" + NEWEST_FIRST, (rs, i) -> read(rs.getString(1)));
        }
        return jdbc.query("SELECT doc FROM releases WHERE workflow = ?" + NEWEST_FIRST, (rs, i) -> read(rs.getString(1)), workflow);
    }

    @Override
    public List<Release> active() {
        return jdbc.query("SELECT doc FROM releases WHERE " + ACTIVE + NEWEST_FIRST, (rs, i) -> read(rs.getString(1)));
    }

    @Override
    public Optional<Release> active(String workflow) {
        return jdbc.first("SELECT doc FROM releases WHERE workflow = ? AND " + ACTIVE + NEWEST_FIRST + " LIMIT 1", (rs, i) -> read(rs.getString(1)), workflow);
    }

    private Release read(String doc) {
        return jdbc.read(doc, Release.class);
    }
}
