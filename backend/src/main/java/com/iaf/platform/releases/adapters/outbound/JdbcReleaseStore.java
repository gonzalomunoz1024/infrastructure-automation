package com.iaf.platform.releases.adapters.outbound;

import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
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
 * A workflow release is found by its {@code workflow} column, a provider release by its
 * {@code provider} column; {@link #list(String)} with no workflow returns both kinds, as in memory.
 */
@Component
@Primary
@Profile("postgres")
public class JdbcReleaseStore implements ReleaseStore {

    private static final Comparator<Release> NEWEST_FIRST = Comparator
            .comparing((Release r) -> r.startedAt() == null ? Instant.EPOCH : r.startedAt()).reversed()
            .thenComparing(Release::id);
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
        jdbc.upsert("UPDATE releases SET workflow = ?, provider = ?, status = ?, started_at = ?, started_ns = ?, doc = ? WHERE id = ?",
                new Object[] {release.workflow(), release.provider(), status, Jdbc.time(release.startedAt()), Jdbc.nanos(release.startedAt()), doc,
                        release.id()},
                "INSERT INTO releases (id, workflow, provider, status, started_at, started_ns, doc) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                new Object[] {release.id(), release.workflow(), release.provider(), status, Jdbc.time(release.startedAt()), Jdbc.nanos(release.startedAt()),
                        doc});
        return release;
    }

    @Override
    public Optional<Release> find(String id) {
        return id == null ? Optional.empty() : jdbc.first("SELECT doc FROM releases WHERE id = ?", (rs, i) -> read(rs.getString(1)), id);
    }

    @Override
    public List<Release> list(String workflow) {
        if (workflow == null || workflow.isBlank()) {
            return newestFirst(jdbc.query("SELECT doc FROM releases", (rs, i) -> read(rs.getString(1))));
        }
        return newestFirst(jdbc.query("SELECT doc FROM releases WHERE workflow = ?", (rs, i) -> read(rs.getString(1)), workflow));
    }

    @Override
    public List<Release> listProvider(String provider) {
        if (provider == null) {
            return List.of();
        }
        return newestFirst(jdbc.query("SELECT doc FROM releases WHERE provider = ?", (rs, i) -> read(rs.getString(1)), provider));
    }

    @Override
    public List<Release> active() {
        return newestFirst(jdbc.query("SELECT doc FROM releases WHERE " + ACTIVE, (rs, i) -> read(rs.getString(1))));
    }

    @Override
    public Optional<Release> active(String workflow) {
        if (workflow == null) {
            return Optional.empty();
        }
        return newestFirst(jdbc.query("SELECT doc FROM releases WHERE workflow = ? AND " + ACTIVE, (rs, i) -> read(rs.getString(1)), workflow))
                .stream().findFirst();
    }

    @Override
    public Optional<Release> activeProvider(String provider) {
        if (provider == null) {
            return Optional.empty();
        }
        return newestFirst(jdbc.query("SELECT doc FROM releases WHERE provider = ? AND " + ACTIVE, (rs, i) -> read(rs.getString(1)), provider))
                .stream().findFirst();
    }

    private Release read(String doc) {
        return jdbc.read(doc, Release.class);
    }

    private static List<Release> newestFirst(List<Release> releases) {
        return releases.stream().sorted(NEWEST_FIRST).toList();
    }
}
