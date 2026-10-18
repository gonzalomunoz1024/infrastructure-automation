package com.iaf.platform.workflows.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.providers.domain.ProviderDefinition;

/**
 * What the checks may look up: the providers, published workflows and resource types a
 * definition can refer to. An empty context (CI without the catalog) skips those checks. The
 * schemas (a published workflow's input and outputs, a resource type's attributes) are optional:
 * without them the contract of run steps and the fields a step returns are not judged.
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

    /** A published workflow's input schema ({@code api.input}), for the contract of run steps; empty when unknown. */
    default Optional<JsonNode> workflowInput(String name) {
        return Optional.empty();
    }

    /** The output names of a published workflow (the keys of {@code workflow.outputs}); empty when unknown. */
    default Optional<List<String>> workflowOutputs(String name) {
        return Optional.empty();
    }

    /** A resource type's attribute schema; empty when the type or its schema is unknown. */
    default Optional<JsonNode> resourceTypeSchema(String type) {
        return Optional.empty();
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

    /**
     * The full catalog: the providers, the published definitions (their names, input schemas and
     * outputs) and every resource type with its attribute schema (null when the file has none).
     */
    static CheckContext of(Map<String, ProviderDefinition> providers, Collection<WorkflowDefinition> published, Map<String, JsonNode> resourceTypes) {
        Map<String, WorkflowDefinition> byName = new LinkedHashMap<>();
        published.forEach(d -> byName.put(d.name(), d));
        Map<String, JsonNode> types = new HashMap<>(resourceTypes);
        Set<String> names = Set.copyOf(byName.keySet());
        Set<String> typeNames = Set.copyOf(types.keySet());
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
                return names;
            }

            @Override
            public Set<String> resourceTypes() {
                return typeNames;
            }

            @Override
            public Optional<JsonNode> workflowInput(String name) {
                return Optional.ofNullable(byName.get(name)).map(WorkflowDefinition::inputSchema);
            }

            @Override
            public Optional<List<String>> workflowOutputs(String name) {
                return Optional.ofNullable(byName.get(name)).map(d -> d.workflow() == null || d.workflow().outputs() == null
                        ? List.<String>of() : List.copyOf(new ArrayList<>(d.workflow().outputs().keySet())));
            }

            @Override
            public Optional<JsonNode> resourceTypeSchema(String type) {
                return Optional.ofNullable(type == null ? null : types.get(type));
            }
        };
    }
}
