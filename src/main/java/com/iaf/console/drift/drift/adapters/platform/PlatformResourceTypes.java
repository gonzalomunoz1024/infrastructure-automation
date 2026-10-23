package com.iaf.console.drift.drift.adapters.platform;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.drift.drift.domain.TierRules;
import com.iaf.console.drift.drift.domain.TypeInfo;
import com.iaf.console.drift.drift.ports.outbound.ResourceTypesPort;
import com.iaf.console.platform.client.PlatformPort;
import com.iaf.console.platform.model.ObserveSpec;
import com.iaf.console.platform.model.ResourceType;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The resource types from the platform ({@code GET /api/v1/resource-types}, P-06), read with the
 * ETag and kept for a short while; when the platform cannot be reached the last good set serves.
 * What drift keeps of a type: its observe action and list/discover reads, the ignored fields, the
 * interval, what not-found means, and which fields the update workflow may change; for monitoring
 * and rightsizing, its metrics read and the numeric values its schema allows for a field.
 */
public final class PlatformResourceTypes implements ResourceTypesPort {

    private static final Logger log = LoggerFactory.getLogger(PlatformResourceTypes.class);
    private static final Duration DEFAULT_INTERVAL = Duration.ofHours(1);

    private record Snapshot(Map<String, TypeInfo> byName, String etag) {
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
    public Mono<TypeInfo> find(String name) {
        if (name == null) {
            return Mono.empty();
        }
        return cached.flatMap(s -> Mono.justOrEmpty(s.byName().get(name)));
    }

    @Override
    public Flux<TypeInfo> list() {
        return cached.flatMapMany(s -> Flux.fromIterable(s.byName().values()));
    }

    /** Forgets the cached set, so the next read asks the platform. */
    public void refresh() {
        cached = fresh();
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
                    Map<String, TypeInfo> byName = new HashMap<>();
                    for (ResourceType type : conditional.body() == null ? List.<ResourceType>of() : conditional.body()) {
                        byName.put(type.name(), info(type));
                    }
                    Snapshot snapshot = new Snapshot(Map.copyOf(byName), conditional.etag());
                    last = snapshot;
                    log.info("{} resource type(s) read from the platform, {} observable", byName.size(),
                            byName.values().stream().filter(TypeInfo::observable).count());
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

    /** What drift keeps of a platform type. */
    public static TypeInfo info(ResourceType type) {
        Set<String> mutable = new HashSet<>();
        List<String> fields = new ArrayList<>();
        Map<String, List<Double>> enums = new HashMap<>();
        if (type.schema() != null) {
            JsonNode properties = type.schema().path("properties");
            properties.fieldNames().forEachRemaining(field -> {
                fields.add(field);
                if (properties.path(field).path("x-console").path("mutable").asBoolean(false)) {
                    mutable.add(field);
                }
                List<Double> allowed = numbers(properties.path(field).path("enum"));
                if (!allowed.isEmpty()) {
                    enums.put(field, allowed);
                }
            });
        }
        ObserveSpec observe = type.observe();
        Duration interval = DEFAULT_INTERVAL;
        if (observe != null && observe.interval() != null && !observe.interval().isBlank()) {
            Duration parsed = TierRules.parseInterval(observe.interval());
            if (parsed != null && !parsed.isZero() && !parsed.isNegative()) {
                interval = parsed;
            } else {
                log.warn("type {}: observe.interval '{}' does not parse, using {}", type.name(), observe.interval(), DEFAULT_INTERVAL);
            }
        }
        return new TypeInfo(type.name(), type.plural(), type.service(),
                observe == null ? null : observe.provider(),
                observe == null ? null : observe.action(),
                observe == null || observe.map() == null ? Map.of() : Map.copyOf(observe.map()),
                observe == null || observe.ignore() == null ? Set.of() : Set.copyOf(observe.ignore()),
                interval,
                observe == null ? null : observe.notFound(),
                observe != null && observe.list() != null,
                observe != null && observe.discover() != null,
                Set.copyOf(mutable),
                List.copyOf(fields),
                observe == null || observe.metrics() == null || observe.metrics().action() == null ? null
                        : new TypeInfo.Metrics(observe.metrics().action(),
                                observe.metrics().map() == null ? List.of() : List.copyOf(observe.metrics().map().keySet())),
                enums);
    }

    /** The values of a schema {@code enum} when every one is a number; empty otherwise (a list of names is no size). */
    private static List<Double> numbers(JsonNode values) {
        if (!values.isArray() || values.isEmpty()) {
            return List.of();
        }
        List<Double> out = new ArrayList<>();
        for (JsonNode v : values) {
            if (!v.isNumber()) {
                return List.of();
            }
            out.add(v.asDouble());
        }
        return List.copyOf(out);
    }
}
