package com.iaf.platform.resources.adapters.outbound;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.resources.domain.Resource;
import com.iaf.platform.resources.domain.ResourceTypeDefinition;
import com.iaf.platform.resources.ports.outbound.ResourceStorePort;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * The demo registry: resource types read from {@code <content dir>/resource-types}, and, when
 * {@code platform.demo.seed-history} is on, a few resources per type with lineage between them.
 * With the {@code mongo} profile, {@link MongoResourceStore} keeps the registry instead.
 */
@Slf4j
@Profile("!mongo")
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
        Path root = ResourceSeeds.root(props);
        ResourceSeeds.types(root).forEach(this::saveType);
        log.info("resource registry: {} types from {}", types.size(), root.resolve("resource-types"));
        if (props.getDemo().isSeedHistory() && !types.isEmpty()) {
            int added = 0;
            for (Resource r : ResourceSeeds.demo(root, types(), clock.instant())) {
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
    public List<Resource> builtFrom(String id) {
        return id == null ? List.of() : resources().stream().filter(r -> r.builtFrom() != null && r.builtFrom().contains(id)).toList();
    }

    @Override
    public void save(Resource resource) {
        resources.put(resource.id(), resource);
    }
}
