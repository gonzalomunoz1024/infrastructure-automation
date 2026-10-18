package com.iaf.platform.workflows.adapters.outbound;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

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
 * The demo store: every {@code kind: Workflow} file under the content dir becomes a published
 * version at startup; drafts and later versions live in memory until the process ends. With the
 * {@code mongo} profile, {@link MongoWorkflowStore} keeps them instead.
 */
@Slf4j
@Profile("!mongo")
@Component
public class InMemoryWorkflowStore implements WorkflowStorePort {

    /** name -> version -> record */
    private final Map<String, Map<String, WorkflowRecord>> records = new ConcurrentHashMap<>();
    private final Path root;
    private final Clock clock;

    @Autowired
    public InMemoryWorkflowStore(PlatformProperties props, Clock clock) {
        this(Path.of(props.getContent().getDir()), clock);
    }

    /** @param root the content dir to seed from at {@link #init()}; null seeds nothing */
    public InMemoryWorkflowStore(Path root, Clock clock) {
        this.root = root;
        this.clock = clock;
    }

    /** An empty store, for tests. */
    public static InMemoryWorkflowStore empty(Clock clock) {
        return new InMemoryWorkflowStore((Path) null, clock);
    }

    @PostConstruct
    public void init() {
        if (root != null) {
            seed(root);
        }
    }

    /** Every {@code kind: Workflow} file under {@code root} becomes a PUBLISHED record with its path relative to the root. */
    public int seed(Path root) {
        Path abs = root.toAbsolutePath().normalize();
        int count = 0;
        Instant now = clock.instant();
        for (ContentFiles.ContentFile f : ContentFiles.ofKind(abs, WorkflowDefinition.KIND)) {
            try {
                WorkflowDefinition d = WorkflowYaml.parse(f.yaml());
                WorkflowRecord r = WorkflowRecord.of(d, WorkflowStatus.PUBLISHED, f.relative(abs), now, "content").published(now, "content", null);
                save(r);
                count++;
            } catch (IllegalArgumentException e) {
                log.warn("skipping {}: {}", f.path(), e.getMessage());
            }
        }
        log.info("seeded {} published workflow(s) from {}", count, abs);
        return count;
    }

    @Override
    public List<WorkflowRecord> all() {
        List<WorkflowRecord> out = new ArrayList<>();
        records.keySet().stream().sorted().forEach(n -> out.addAll(versions(n)));
        return out;
    }

    @Override
    public List<WorkflowRecord> versions(String name) {
        Map<String, WorkflowRecord> m = records.get(name);
        if (m == null) {
            return List.of();
        }
        return m.values().stream().sorted((a, b) -> Semver.ORDER.compare(a.version(), b.version())).toList();
    }

    @Override
    public Optional<WorkflowRecord> get(String name, String version) {
        Map<String, WorkflowRecord> m = records.get(name);
        return m == null ? Optional.empty() : Optional.ofNullable(m.get(version));
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
        records.computeIfAbsent(record.name(), n -> new ConcurrentHashMap<>()).put(record.version(), record);
    }

    @Override
    public void delete(String name, String version) {
        Map<String, WorkflowRecord> m = records.get(name);
        if (m != null) {
            m.remove(version);
            if (m.isEmpty()) {
                records.remove(name);
            }
        }
    }
}
