package com.iaf.platform.providers.ports.outbound;

import java.util.List;
import java.util.Optional;

import com.iaf.platform.providers.domain.ProviderDefinition;

/** Where provider files live (Git or memory seeded from the content dir). One record per provider; versions are in Git. */
public interface ProviderStorePort {

    record StoredProvider(ProviderDefinition definition, String yaml, String path, java.time.Instant updatedAt, String updatedBy) {
    }

    List<StoredProvider> all();

    Optional<StoredProvider> get(String name);

    void save(StoredProvider provider);

    void delete(String name);
}
