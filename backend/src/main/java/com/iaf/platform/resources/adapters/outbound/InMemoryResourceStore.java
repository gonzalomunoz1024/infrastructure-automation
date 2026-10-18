package com.iaf.platform.resources.adapters.outbound;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

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
 * The demo registry: resource types read from {@code <content dir>/resource-types}, and, when
 * {@code platform.demo.seed-history} is on, a few resources per type with lineage between them.
 */
@Slf4j
@Profile("!postgres")
@Component
public class InMemoryResourceStore implements ResourceStorePort {

    private final PlatformProperties props;
    private final Clock clock;
    private final Map<String, ResourceTypeDefinition> types = new ConcurrentHashMap<>();
    private final Map<String, Resource> resources = new ConcurrentHashMap<>();

    public InMemoryResourceStore(PlatformProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    @PostConstruct
    public void seed() {
        Path root = Path.of(props.getContent().getDir()).toAbsolutePath().normalize();
        List<ContentFiles.ContentFile> files = ContentFiles.ofKind(root, ResourceTypeDefinition.KIND);
        for (ContentFiles.ContentFile f : files) {
            try {
                saveType(ResourceTypeDefinition.parse(f.yaml()));
            } catch (IllegalArgumentException e) {
                log.warn("skipping resource type {}: {}", f.relative(root), e.getMessage());
            }
        }
        log.info("resource registry: {} types from {}", types.size(), root.resolve("resource-types"));
        if (props.getDemo().isSeedHistory() && !types.isEmpty()) {
            List<WorkflowDefinition> workflows = new ArrayList<>();
            for (ContentFiles.ContentFile f : ContentFiles.ofKind(root, WorkflowDefinition.KIND)) {
                try {
                    workflows.add(WorkflowYaml.parse(f.yaml()));
                } catch (IllegalArgumentException e) {
                    log.debug("demo seed ignores {}: {}", f.relative(root), e.getMessage());
                }
            }
            int added = 0;
            for (Resource r : DemoResources.seed(types(), workflows, clock.instant())) {
                if (resources.putIfAbsent(r.id(), r) == null) {
                    added++;
                }
            }
            log.info("resource registry: {} demo resources seeded", added);
        }
    }

    @Override
    public List<ResourceTypeDefinition> types() {
        return types.values().stream().sorted(Comparator.comparing(ResourceTypeDefinition::name)).toList();
    }

    @Override
    public Optional<ResourceTypeDefinition> type(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(types.get(name));
    }

    @Override
    public void saveType(ResourceTypeDefinition type) {
        types.put(type.name(), type);
    }

    @Override
    public List<Resource> resources() {
        return resources.values().stream()
                .sorted(Comparator.comparing(Resource::type).thenComparing(Resource::createdAt, Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(Resource::id))
                .toList();
    }

    @Override
    public Optional<Resource> resource(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(resources.get(id));
    }

    @Override
    public void save(Resource resource) {
        resources.put(resource.id(), resource);
    }
}
