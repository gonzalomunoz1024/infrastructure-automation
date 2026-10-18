package com.iaf.platform.workflows.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.catalog.domain.McpTools;
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

    /**
     * The input schema of the version a run step with this {@code version} range starts: the
     * highest published or retired version inside it ({@link DependencyRules#resolve}), the
     * current published one for a step without a range. Empty when no version is in range or the
     * workflow is unknown. A context that knows one version per workflow answers that one.
     */
    default Optional<JsonNode> workflowInput(String name, String range) {
        return workflowInput(name);
    }

    /** The output names of the version a run step with this range starts, as {@link #workflowInput(String, String)} picks it. */
    default Optional<List<String>> workflowOutputs(String name, String range) {
        return workflowOutputs(name);
    }

    /** A resource type's attribute schema; empty when the type or its schema is unknown. */
    default Optional<JsonNode> resourceTypeSchema(String type) {
        return Optional.empty();
    }

    /**
     * The workflows a published workflow starts (its run steps and {@code orCreateWith} inputs), by
     * name; empty when it starts none or is unknown. With them a run step whose workflow ends up
     * starting this one again is a cycle ({@code run.cycle}).
     */
    default Set<String> workflowRuns(String name) {
        return Set.of();
    }

    /**
     * The published workflows the MCP server gives a tool of this name ({@code vm_restart}, or the
     * batch tool {@code vm_restart_batch}), by name; empty when none does or the registry is not
     * known.
     */
    default Set<String> mcpToolHolders(String tool) {
        return Set.of();
    }

    /** Whether the vault's credentials were read; when false, whose a credential is is not judged. */
    default boolean knowsCredentials() {
        return false;
    }

    /**
     * The owner team of the credential a {@code vault:} reference names, when the vault keeps the
     * credential and it has an owner; empty for an unowned credential (any team's) and for one
     * the vault does not keep (the run finds that out).
     */
    default Optional<String> credentialOwner(String reference) {
        return Optional.empty();
    }

    /**
     * This context with provider files that are not published yet ({@link ProviderDrafts}) in
     * front of its providers: a draft stands in for the provider of its name, or joins them under
     * a new name, and everything else is this context's. A method added to this interface must be
     * passed on here too.
     */
    default CheckContext withProviders(List<ProviderDefinition> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            return this;
        }
        CheckContext base = this;
        List<ProviderDefinition> own = List.copyOf(drafts);
        Set<String> names = new TreeSet<>(base.providerNames());
        own.forEach(d -> names.add(d.name()));
        Set<String> allNames = Set.copyOf(names);
        return new CheckContext() {
            @Override
            public Optional<ProviderDefinition> provider(String name) {
                return ProviderDrafts.named(own, name).or(() -> base.provider(name));
            }

            @Override
            public Set<String> providerNames() {
                return allNames;
            }

            @Override
            public Set<String> workflowNames() {
                return base.workflowNames();
            }

            @Override
            public Set<String> resourceTypes() {
                return base.resourceTypes();
            }

            @Override
            public boolean knowsCatalog() {
                return base.knowsCatalog();
            }

            @Override
            public Optional<JsonNode> workflowInput(String name) {
                return base.workflowInput(name);
            }

            @Override
            public Optional<List<String>> workflowOutputs(String name) {
                return base.workflowOutputs(name);
            }

            @Override
            public Optional<JsonNode> workflowInput(String name, String range) {
                return base.workflowInput(name, range);
            }

            @Override
            public Optional<List<String>> workflowOutputs(String name, String range) {
                return base.workflowOutputs(name, range);
            }

            @Override
            public Optional<JsonNode> resourceTypeSchema(String type) {
                return base.resourceTypeSchema(type);
            }

            @Override
            public Set<String> workflowRuns(String name) {
                return base.workflowRuns(name);
            }

            @Override
            public Set<String> mcpToolHolders(String tool) {
                return base.mcpToolHolders(tool);
            }

            @Override
            public boolean knowsCredentials() {
                return base.knowsCredentials();
            }

            @Override
            public Optional<String> credentialOwner(String reference) {
                return base.credentialOwner(reference);
            }
        };
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
        return of(providers, published, resourceTypes, null);
    }

    /**
     * The full catalog and the owner team of each credential the vault keeps, by name
     * ({@code vsphere/token} for {@code vault:vsphere/token}; unowned ones left out). A null map:
     * the credentials could not be read, and their owners are not judged.
     */
    static CheckContext of(Map<String, ProviderDefinition> providers, Collection<WorkflowDefinition> published, Map<String, JsonNode> resourceTypes,
            Map<String, String> credentialOwners) {
        return of(providers, published, published, resourceTypes, credentialOwners);
    }

    /**
     * As {@link #of(Map, Collection, Map, Map)}, with every version a run step can start
     * ({@code runnable}: the published and retired versions, rolled-back ones left out), so a step
     * pinned to an older major is judged against that major's input and outputs.
     */
    static CheckContext of(Map<String, ProviderDefinition> providers, Collection<WorkflowDefinition> published, Collection<WorkflowDefinition> runnable,
            Map<String, JsonNode> resourceTypes, Map<String, String> credentialOwners) {
        Map<String, String> owners = credentialOwners == null ? null : Map.copyOf(credentialOwners);
        Map<String, WorkflowDefinition> byName = new LinkedHashMap<>();
        published.forEach(d -> byName.put(d.name(), d));
        Map<String, Map<String, WorkflowDefinition>> versions = new HashMap<>();
        for (WorkflowDefinition d : runnable) {
            versions.computeIfAbsent(d.name(), k -> new LinkedHashMap<>()).putIfAbsent(d.version(), d);
        }
        byName.values().forEach(d -> versions.computeIfAbsent(d.name(), k -> new LinkedHashMap<>()).putIfAbsent(d.version(), d));
        Map<String, JsonNode> types = new HashMap<>(resourceTypes);
        Set<String> names = Set.copyOf(byName.keySet());
        Set<String> typeNames = Set.copyOf(types.keySet());
        Map<String, Set<String>> tools = new HashMap<>();
        byName.values().forEach(d -> McpTools.toolNames(d).forEach(t -> tools.computeIfAbsent(t, k -> new TreeSet<>()).add(d.name())));
        Map<String, Set<String>> runs = DependencyRules.runs(byName.values());
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
                return Optional.ofNullable(byName.get(name)).map(CheckContext::outputNames);
            }

            @Override
            public Optional<JsonNode> workflowInput(String name, String range) {
                return inRange(name, range).map(WorkflowDefinition::inputSchema);
            }

            @Override
            public Optional<List<String>> workflowOutputs(String name, String range) {
                return inRange(name, range).map(CheckContext::outputNames);
            }

            private Optional<WorkflowDefinition> inRange(String name, String range) {
                if (range == null || range.isBlank()) {
                    return Optional.ofNullable(byName.get(name));
                }
                Map<String, WorkflowDefinition> known = versions.get(name);
                return known == null ? Optional.empty() : DependencyRules.resolve(known.values(), range);
            }

            @Override
            public Optional<JsonNode> resourceTypeSchema(String type) {
                return Optional.ofNullable(type == null ? null : types.get(type));
            }

            @Override
            public Set<String> workflowRuns(String name) {
                return name == null ? Set.of() : runs.getOrDefault(name, Set.of());
            }

            @Override
            public Set<String> mcpToolHolders(String tool) {
                return tool == null ? Set.of() : tools.getOrDefault(tool, Set.of());
            }

            @Override
            public boolean knowsCredentials() {
                return owners != null;
            }

            @Override
            public Optional<String> credentialOwner(String reference) {
                if (owners == null || reference == null) {
                    return Optional.empty();
                }
                String r = reference.trim();
                return Optional.ofNullable(owners.get(r.startsWith("vault:") ? r.substring("vault:".length()) : r));
            }
        };
    }

    /** The keys of a definition's {@code workflow.outputs}, in order. */
    private static List<String> outputNames(WorkflowDefinition d) {
        return d.workflow() == null || d.workflow().outputs() == null ? List.of() : List.copyOf(new ArrayList<>(d.workflow().outputs().keySet()));
    }
}
