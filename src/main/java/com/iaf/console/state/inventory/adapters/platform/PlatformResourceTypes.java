package com.iaf.console.state.inventory.adapters.platform;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.platform.client.PlatformPort;
import com.iaf.console.platform.model.ResourceType;
import com.iaf.console.state.inventory.domain.ResourceTypeInfo;
import com.iaf.console.state.inventory.ports.outbound.ResourceTypesPort;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The resource types from the platform ({@code GET /api/v1/resource-types}, P-06), read with the
 * ETag and kept for a short while; when the platform cannot be reached the last good set serves.
 */
public final class PlatformResourceTypes implements ResourceTypesPort {

    private static final Logger log = LoggerFactory.getLogger(PlatformResourceTypes.class);

    private record Snapshot(Map<String, ResourceTypeInfo> byName, String etag) {
    }

    private final PlatformPort platform;
    private final Duration ttl;
    private volatile Snapshot last;
    private volatile Mono<Snapshot> cached;

    public PlatformResourceTypes(PlatformPort platform, Duration ttl) {
        this.platform = platform;
        this.ttl = ttl;
        this.cached = fresh();
    }

    @Override
    public Mono<ResourceTypeInfo> find(String name) {
        if (name == null) {
            return Mono.empty();
        }
        return current().flatMap(s -> Mono.justOrEmpty(s.byName().get(name)));
    }

    @Override
    public Flux<ResourceTypeInfo> list() {
        return current().flatMapMany(s -> Flux.fromIterable(s.byName().values()));
    }

    /** Forgets the cached set, so the next read asks the platform. */
    public void refresh() {
        cached = fresh();
    }

    private Mono<Snapshot> current() {
        return cached;
    }

    private Mono<Snapshot> fresh() {
        return Mono.defer(this::load).cache(s -> ttl, e -> Duration.ZERO, () -> Duration.ZERO);
    }

    private Mono<Snapshot> load() {
        Snapshot previous = last;
        return platform.listResourceTypes(previous == null ? null : previous.etag())
                .map(conditional -> {
                    if (conditional.notModified() && previous != null) {
                        return previous;
                    }
                    Map<String, ResourceTypeInfo> byName = new HashMap<>();
                    for (ResourceType type : conditional.body() == null ? List.<ResourceType>of() : conditional.body()) {
                        byName.put(type.name(), info(type));
                    }
                    Snapshot snapshot = new Snapshot(Map.copyOf(byName), conditional.etag());
                    last = snapshot;
                    log.info("{} resource type(s) read from the platform", byName.size());
                    return snapshot;
                })
                .onErrorResume(e -> {
                    if (previous != null) {
                        log.warn("resource types could not be refreshed, serving the last set: {}", e.toString());
                        return Mono.just(previous);
                    }
                    return Mono.error(e);
                });
    }

    /** What the inventory keeps of a platform type. */
    public static ResourceTypeInfo info(ResourceType type) {
        Set<String> sensitive = new HashSet<>();
        Set<String> mutable = new HashSet<>();
        if (type.schema() != null) {
            JsonNode properties = type.schema().path("properties");
            properties.fieldNames().forEachRemaining(field -> {
                JsonNode hints = properties.path(field).path("x-console");
                if (hints.path("sensitive").asBoolean(false)) {
                    sensitive.add(field);
                }
                if (hints.path("mutable").asBoolean(false)) {
                    mutable.add(field);
                }
            });
        }
        Map<String, String> lifecycle = type.console() == null || type.console().lifecycle() == null ? Map.of() : type.console().lifecycle();
        String notFound = type.observe() == null ? null : type.observe().notFound();
        Set<String> ignored = type.observe() == null || type.observe().ignore() == null ? Set.of() : new HashSet<>(type.observe().ignore());
        return new ResourceTypeInfo(type.name(), type.plural(), type.service(), lifecycle, notFound, sensitive, mutable, ignored);
    }
}
