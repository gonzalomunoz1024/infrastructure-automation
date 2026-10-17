package com.iaf.platform.workflows.domain;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.iaf.platform.providers.domain.ProviderDefinition;

/**
 * What the checks may look up: the providers, published workflows and resource types a
 * definition can refer to. An empty context (CI without the catalog) skips those checks.
 */
public interface CheckContext {

    Optional<ProviderDefinition> provider(String name);

    Set<String> providerNames();

    /** Published workflows by name, for {@code run} steps and {@code orCreateWith}. */
    Set<String> workflowNames();

    Set<String> resourceTypes();

    /** Whether the catalog is known at all; when false, unknown names are not reported. */
    default boolean knowsCatalog() {
        return true;
    }

    CheckContext EMPTY = new CheckContext() {
        @Override
        public Optional<ProviderDefinition> provider(String name) {
            return Optional.empty();
        }

        @Override
        public Set<String> providerNames() {
            return Set.of();
        }

        @Override
        public Set<String> workflowNames() {
            return Set.of();
        }

        @Override
        public Set<String> resourceTypes() {
            return Set.of();
        }

        @Override
        public boolean knowsCatalog() {
            return false;
        }
    };

    static CheckContext of(Map<String, ProviderDefinition> providers, Set<String> workflows, Set<String> resourceTypes) {
        return new CheckContext() {
            @Override
            public Optional<ProviderDefinition> provider(String name) {
                return Optional.ofNullable(providers.get(name));
            }

            @Override
            public Set<String> providerNames() {
                return providers.keySet();
            }

            @Override
            public Set<String> workflowNames() {
                return workflows;
            }

            @Override
            public Set<String> resourceTypes() {
                return resourceTypes;
            }
        };
    }
}
