package com.iaf.console.state.inventory.domain;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What the inventory needs to know of a resource type (P-06): the service that names it in a wfrn,
 * how the platform's statuses map to lifecycle classes, what a provider's "not found" means, and
 * which fields are sensitive, mutable or ignored by drift.
 */
public record ResourceTypeInfo(
        String name,
        String plural,
        String service,
        Map<String, String> lifecycle,
        String notFound,
        Set<String> sensitiveFields,
        Set<String> mutableFields,
        Set<String> ignoredFields) {

    /** The service P-06 gives a type that names none. */
    public static final String DEFAULT_SERVICE = "platform";

    public ResourceTypeInfo {
        Objects.requireNonNull(name, "name");
        plural = plural == null ? name + "s" : plural;
        service = service == null || service.isBlank() ? DEFAULT_SERVICE : service;
        lifecycle = lifecycle == null ? Map.of() : Map.copyOf(lifecycle);
        notFound = notFound == null ? "missing" : notFound;
        sensitiveFields = sensitiveFields == null ? Set.of() : Set.copyOf(sensitiveFields);
        mutableFields = mutableFields == null ? Set.of() : Set.copyOf(mutableFields);
        ignoredFields = ignoredFields == null ? Set.of() : Set.copyOf(ignoredFields);
    }

    /** A type the inventory knows nothing about beyond its name, in the default service. */
    public static ResourceTypeInfo unknown(String name) {
        return new ResourceTypeInfo(name, null, null, null, null, null, null, null);
    }

    /** The lifecycle class of a platform status: the type's map, else the platform's conventional names. */
    public ResourceState lifecycleOf(String platformStatus) {
        return Lifecycle.classOf(lifecycle, platformStatus);
    }

    /** Whether a provider's "not found" means the resource is gone ({@code notFound: deleted}). */
    public boolean notFoundMeansDeleted() {
        return "deleted".equals(notFound);
    }
}
