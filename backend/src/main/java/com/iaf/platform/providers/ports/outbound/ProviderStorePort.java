package com.iaf.platform.providers.ports.outbound;

import java.util.List;
import java.util.Optional;

import com.iaf.platform.providers.domain.ProviderDefinition;

/**
 * Where provider files live (Git or memory seeded from the content dir). One current record per
 * provider, which {@link #all()} and {@link #get(String)} return; earlier versions are in Git. A
 * version being rolled out by a canary is kept beside the current one until it is promoted.
 */
public interface ProviderStorePort {

    record StoredProvider(ProviderDefinition definition, String yaml, String path, java.time.Instant updatedAt, String updatedBy) {
    }

    List<StoredProvider> all();

    Optional<StoredProvider> get(String name);

    /** Makes this version the provider's current one. */
    void save(StoredProvider provider);

    void delete(String name);

    /** The versions of a provider the store keeps, newest first. A store that keeps only the current file returns just that one. */
    default List<StoredProvider> versions(String name) {
        return get(name).map(List::of).orElse(List.of());
    }

    /** One kept version of a provider: the current one or a canary candidate. */
    default Optional<StoredProvider> version(String name, String version) {
        if (version == null) {
            return Optional.empty();
        }
        return versions(name).stream().filter(p -> version.equals(p.definition().metadata().version())).findFirst();
    }

    /**
     * Keeps a version without making it current: a canary candidate, which only the calls the
     * release routes to it use. A store that cannot keep versions makes it current instead.
     */
    default void saveVersion(StoredProvider provider) {
        save(provider);
    }
}
