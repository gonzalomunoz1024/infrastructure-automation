package com.iaf.platform.workflows.adapters.outbound;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.platform.common.adapters.jdbc.Jdbc;
import com.iaf.platform.common.adapters.jdbc.Revisioned;
import com.iaf.platform.common.content.ContentFiles;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.workflows.domain.Semver;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowRecord;
import com.iaf.platform.workflows.domain.WorkflowStatus;
import com.iaf.platform.workflows.domain.WorkflowYaml;
import com.iaf.platform.workflows.ports.outbound.WorkflowStorePort;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * The workflow store of the {@code postgres} profile: every version of every workflow is a row of
 * {@code workflow_versions}, its doc the whole {@link WorkflowRecord} (the YAML as written and
 * the parsed definition, so a definition comes back exactly as it was saved). At startup each
 * {@code kind: Workflow} file under the content dir that the database does not hold yet becomes
 * a published version, as in memory; a stored version is never reset by its file, except that a
 * version still exactly as seeded takes the file's new content. Reads come from a copy of the
 * table that is reloaded whenever any instance has written to it.
 *
 * <p>With {@code platform.content.git} on, the content repo stays the store
 * ({@link GitWorkflowStore}): Git is durable already.
 */
@Slf4j
@Component
@Primary
@Profile("postgres")
@ConditionalOnProperty(name = "platform.content.git", havingValue = "false", matchIfMissing = true)
public class JdbcWorkflowStore implements WorkflowStorePort {

    static final String STORE = "workflows";
    static final String SEED_AUTHOR = "content";

    private final Jdbc jdbc;
    private final Path root;
    private final Clock clock;
    private final Revisioned<Index> table;

    @Autowired
    public JdbcWorkflowStore(Jdbc jdbc, PlatformProperties props, Clock clock) {
        this(jdbc, Path.of(props.getContent().getDir()), clock);
    }

    /** @param root the content dir to seed from at {@link #init()}; null seeds nothing */
    public JdbcWorkflowStore(Jdbc jdbc, Path root, Clock clock) {
        this.jdbc = jdbc;
        this.root = root;
        this.clock = clock;
        this.table = new Revisioned<>(jdbc, STORE, this::load);
    }

    @PostConstruct
    public void init() {
        if (root != null) {
            seed(root);
        }
    }

    /**
     * Every {@code kind: Workflow} file under {@code root} the database does not hold becomes a
     * PUBLISHED version; a version still as seeded ({@code updatedBy} content) takes a changed
     * file's content and keeps its status. Returns how many versions were added or refreshed.
     */
    public int seed(Path root) {
        Path abs = root.toAbsolutePath().normalize();
        Instant now = clock.instant();
        Index stored = table.get();
        List<WorkflowRecord> writes = new ArrayList<>();
        int added = 0;
        for (ContentFiles.ContentFile f : ContentFiles.ofKind(abs, WorkflowDefinition.KIND)) {
            WorkflowDefinition d;
            try {
                d = WorkflowYaml.parse(f.yaml());
            } catch (IllegalArgumentException e) {
                log.warn("skipping {}: {}", f.path(), e.getMessage());
                continue;
            }
            WorkflowRecord seeded = WorkflowRecord.of(d, WorkflowStatus.PUBLISHED, f.relative(abs), now, SEED_AUTHOR).published(now, SEED_AUTHOR, null);
            Optional<WorkflowRecord> existing = stored.get(d.name(), d.version());
            if (existing.isEmpty()) {
                writes.add(seeded);
                added++;
            } else if (SEED_AUTHOR.equals(existing.get().updatedBy()) && !seeded.contentHash().equals(existing.get().contentHash())) {
                WorkflowRecord s = existing.get();
                writes.add(new WorkflowRecord(s.name(), s.version(), s.status(), seeded.yaml(), seeded.definition(), seeded.contentHash(), seeded.path(),
                        s.updatedAt(), s.updatedBy(), s.publishedAt(), s.publishedBy(), s.releaseId(), s.notes(), s.rolledBack()));
            }
        }
        if (!writes.isEmpty()) {
            jdbc.inTransaction(() -> {
                writes.forEach(this::upsert);
                table.bump();
            });
        }
        log.info("workflow store: {} version(s) seeded and {} refreshed from {}", added, writes.size() - added, abs);
        return writes.size();
    }

    @Override
    public List<WorkflowRecord> all() {
        return table.get().all();
    }

    @Override
    public List<WorkflowRecord> versions(String name) {
        return table.get().versions(name);
    }

    @Override
    public Optional<WorkflowRecord> get(String name, String version) {
        return table.get().get(name, version);
    }

    @Override
    public Optional<WorkflowRecord> published(String name) {
        return versions(name).stream().filter(WorkflowRecord::isPublished).max((a, b) -> Semver.ORDER.compare(a.version(), b.version()));
    }

    @Override
    public Optional<WorkflowRecord> draft(String name) {
        return versions(name).stream().filter(r -> r.status() == WorkflowStatus.DRAFT).max((a, b) -> Semver.ORDER.compare(a.version(), b.version()));
    }

    @Override
    public void save(WorkflowRecord record) {
        jdbc.inTransaction(() -> {
            upsert(record);
            table.bump();
        });
    }

    @Override
    public void delete(String name, String version) {
        jdbc.inTransaction(() -> {
            if (jdbc.update("DELETE FROM workflow_versions WHERE name = ? AND version = ?", name, version) > 0) {
                table.bump();
            }
        });
    }

    private void upsert(WorkflowRecord r) {
        String status = r.status() == null ? null : r.status().json();
        String doc = jdbc.json(r);
        jdbc.upsert("UPDATE workflow_versions SET status = ?, updated_at = ?, doc = ? WHERE name = ? AND version = ?",
                new Object[] {status, Jdbc.time(r.updatedAt()), doc, r.name(), r.version()},
                "INSERT INTO workflow_versions (name, version, status, updated_at, doc) VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                new Object[] {r.name(), r.version(), status, Jdbc.time(r.updatedAt()), doc});
    }

    private Index load() {
        return Index.of(jdbc.query("SELECT doc FROM workflow_versions", (rs, i) -> jdbc.read(rs.getString(1), WorkflowRecord.class)));
    }

    /** The table as the in-memory store holds it: names in order, each name's versions oldest first. */
    private record Index(Map<String, List<WorkflowRecord>> byName) {

        static Index of(List<WorkflowRecord> rows) {
            Map<String, List<WorkflowRecord>> byName = new TreeMap<>();
            for (WorkflowRecord r : rows) {
                byName.computeIfAbsent(r.name(), n -> new ArrayList<>()).add(r);
            }
            byName.replaceAll((n, versions) -> versions.stream().sorted((a, b) -> Semver.ORDER.compare(a.version(), b.version())).toList());
            return new Index(byName);
        }

        List<WorkflowRecord> all() {
            return byName.values().stream().flatMap(List::stream).toList();
        }

        List<WorkflowRecord> versions(String name) {
            return name == null ? List.of() : byName.getOrDefault(name, List.of());
        }

        Optional<WorkflowRecord> get(String name, String version) {
            return versions(name).stream().filter(r -> r.version().equals(version)).findFirst();
        }
    }
}
