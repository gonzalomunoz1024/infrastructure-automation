package com.iaf.console.search.index.adapters.platform;

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
import com.iaf.console.search.index.domain.ResourceTypeHints;
import com.iaf.console.search.index.ports.outbound.ResourceTypesPort;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The resource types from the platform ({@code GET /api/v1/resource-types}, P-06), read with the
 * ETag and kept for a short while; when the platform cannot be reached the last good set serves,
 * and before any set was read a type is unknown (its name, wfrn and tags are still indexed).
 */
public final class PlatformResourceTypes implements ResourceTypesPort {

    private static final Logger log = LoggerFactory.getLogger(PlatformResourceTypes.class);

    private record Snapshot(Map<String, ResourceTypeHints> byName, String etag) {
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
    public Mono<ResourceTypeHints> find(String name) {
        if (name == null) {
            return Mono.empty();
        }
        return current().flatMap(s -> Mono.justOrEmpty(s.byName().get(name)));
    }

    @Override
    public Flux<ResourceTypeHints> list() {
        return current().flatMapMany(s -> Flux.fromIterable(s.byName().values()));
    }

    public void refresh() {
        cached = fresh();
    }

    private Mono<Snapshot> current() {
        return cached.onErrorResume(e -> {
            log.warn("resource types are not available, indexing names and tags only: {}", e.toString());
            return Mono.just(new Snapshot(Map.of(), null));
        });
    }

    private Mono<Snapshot> fresh() {
        return Mono.defer(this::load).cache(s -> ttl, e -> Duration.ofSeconds(5), () -> Duration.ZERO);
    }

    private Mono<Snapshot> load() {
        Snapshot previous = last;
        return platform.listResourceTypes(previous == null ? null : previous.etag())
                .map(conditional -> {
                    if (conditional.notModified() && previous != null) {
                        return previous;
                    }
                    Map<String, ResourceTypeHints> byName = new HashMap<>();
                    for (ResourceType type : conditional.body() == null ? List.<ResourceType>of() : conditional.body()) {
                        byName.put(type.name(), hints(type));
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

    /** What the index keeps of a platform type: its searchable and sensitive fields. */
    public static ResourceTypeHints hints(ResourceType type) {
        Set<String> searchable = new HashSet<>();
        Set<String> sensitive = new HashSet<>();
        if (type.schema() != null) {
            JsonNode properties = type.schema().path("properties");
            properties.fieldNames().forEachRemaining(field -> {
                JsonNode hints = properties.path(field).path("x-console");
                if (hints.path("searchable").asBoolean(false)) {
                    searchable.add(field);
                }
                if (hints.path("sensitive").asBoolean(false)) {
                    sensitive.add(field);
                }
            });
        }
        String title = type.console() == null ? null : type.console().title();
        return new ResourceTypeHints(type.name(), type.plural(), title, type.service(), searchable, sensitive);
    }
}
