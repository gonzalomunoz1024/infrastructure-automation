package com.iaf.platform.resources.adapters.outbound;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.platform.common.adapters.jdbc.Jdbc;
import com.iaf.platform.common.adapters.jdbc.Revisioned;
import com.iaf.platform.common.content.ContentFiles;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.resources.domain.DemoResources;
import com.iaf.platform.resources.domain.Resource;
import com.iaf.platform.resources.domain.ResourceTypeDefinition;
import com.iaf.platform.resources.ports.outbound.ResourceStorePort;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowYaml;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * The resource registry of the {@code postgres} profile: every resource a job registered is a row
 * of {@code resources}, so lineage and retirements survive a restart. The resource types are the
 * {@code kind: ResourceType} files of the content dir, written to {@code resource_types} at every
 * start (the files win) and read from a copy reloaded whenever any instance writes. With
 * {@code platform.demo.seed-history} the demo resources are added once (their ids are fixed).
 */
@Slf4j
@Component
@Primary
@Profile("postgres")
public class JdbcResourceStore implements ResourceStorePort {

    static final String TYPES = "resource_types";

    private static final Comparator<Resource> ORDER = Comparator.comparing(Resource::type)
            .thenComparing(Resource::createdAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Resource::id);

    private final Jdbc jdbc;
    private final PlatformProperties props;
    private final Clock clock;
    private final Revisioned<List<ResourceTypeDefinition>> types;

    public JdbcResourceStore(Jdbc jdbc, PlatformProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
        this.types = new Revisioned<>(jdbc, TYPES, () -> jdbc.query("SELECT doc FROM resource_types",
                (rs, i) -> jdbc.read(rs.getString(1), ResourceTypeDefinition.class)).stream()
                .sorted(Comparator.comparing(ResourceTypeDefinition::name)).toList());
    }

    @PostConstruct
    public void seed() {
        Path root = Path.of(props.getContent().getDir()).toAbsolutePath().normalize();
        Map<String, ResourceTypeDefinition> stored = types().stream().collect(Collectors.toMap(ResourceTypeDefinition::name, Function.identity()));
        List<ResourceTypeDefinition> changed = new ArrayList<>();
        for (ContentFiles.ContentFile f : ContentFiles.ofKind(root, ResourceTypeDefinition.KIND)) {
            try {
                ResourceTypeDefinition type = ResourceTypeDefinition.parse(f.yaml());
                if (!type.equals(stored.get(type.name()))) {
                    changed.add(type);
                }
            } catch (IllegalArgumentException e) {
                log.warn("skipping resource type {}: {}", f.relative(root), e.getMessage());
            }
        }
        if (!changed.isEmpty()) {
            jdbc.inTransaction(() -> {
                changed.forEach(this::upsertType);
                types.bump();
            });
        }
        log.info("resource registry: {} types ({} written from {})", types().size(), changed.size(), root.resolve("resource-types"));
        if (props.getDemo().isSeedHistory() && !types().isEmpty()) {
            List<WorkflowDefinition> workflows = new ArrayList<>();
            for (ContentFiles.ContentFile f : ContentFiles.ofKind(root, WorkflowDefinition.KIND)) {
                try {
                    workflows.add(WorkflowYaml.parse(f.yaml()));
                } catch (IllegalArgumentException e) {
                    log.debug("demo seed ignores {}: {}", f.relative(root), e.getMessage());
                }
            }
            int added = jdbc.inTransaction(() -> {
                int n = 0;
                for (Resource r : DemoResources.seed(types(), workflows, clock.instant())) {
                    if (jdbc.insertIfAbsent("INSERT INTO resources (id, type, status, created_at, doc) VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                            r.id(), r.type(), r.status(), Jdbc.time(r.createdAt()), jdbc.json(r))) {
                        n++;
                    }
                }
                return n;
            });
            log.info("resource registry: {} demo resources seeded", added);
        }
    }

    @Override
    public List<ResourceTypeDefinition> types() {
        return types.get();
    }

    @Override
    public Optional<ResourceTypeDefinition> type(String name) {
        return name == null ? Optional.empty() : types().stream().filter(t -> t.name().equals(name)).findFirst();
    }

    @Override
    public void saveType(ResourceTypeDefinition type) {
        jdbc.inTransaction(() -> {
            upsertType(type);
            types.bump();
        });
    }

    @Override
    public List<Resource> resources() {
        List<Resource> all = new ArrayList<>(jdbc.query("SELECT doc FROM resources", (rs, i) -> read(rs.getString(1))));
        all.sort(ORDER);
        return all;
    }

    @Override
    public Optional<Resource> resource(String id) {
        return id == null ? Optional.empty() : jdbc.first("SELECT doc FROM resources WHERE id = ?", (rs, i) -> read(rs.getString(1)), id);
    }

    @Override
    public void save(Resource resource) {
        String doc = jdbc.json(resource);
        jdbc.upsert("UPDATE resources SET type = ?, status = ?, created_at = ?, doc = ? WHERE id = ?",
                new Object[] {resource.type(), resource.status(), Jdbc.time(resource.createdAt()), doc, resource.id()},
                "INSERT INTO resources (id, type, status, created_at, doc) VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                new Object[] {resource.id(), resource.type(), resource.status(), Jdbc.time(resource.createdAt()), doc});
    }

    private void upsertType(ResourceTypeDefinition type) {
        String doc = jdbc.json(type);
        jdbc.upsert("UPDATE resource_types SET doc = ? WHERE name = ?", new Object[] {doc, type.name()},
                "INSERT INTO resource_types (name, doc) VALUES (?, ?) ON CONFLICT DO NOTHING", new Object[] {type.name(), doc});
    }

    private Resource read(String doc) {
        return jdbc.read(doc, Resource.class);
    }
}
