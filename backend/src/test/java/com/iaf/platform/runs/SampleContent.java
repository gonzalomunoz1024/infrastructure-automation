package com.iaf.platform.runs;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.common.content.ContentFiles;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.expressions.ExpressionEngine;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.providers.domain.ProviderYaml;
import com.iaf.platform.resources.domain.ResourceTypeDefinition;
import com.iaf.platform.runs.domain.SampleStepExecutor;
import com.iaf.platform.workflows.domain.Semver;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowYaml;

/** The repo's sample content, loaded once for the runs tests. */
public final class SampleContent {

    public static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    public static final Identity GONZALO = new Identity("gonzalo", "Gonzalo", "gonzalo@example.com",
            List.of("team-compute", "team-platform", "compute-approvers"), List.of("ops", "sre", "admin"), true, Identity.Kind.USER);
    public static final Identity ANA = new Identity("ana", "Ana", "ana@example.com", List.of("team-data", "data-approvers"), List.of("ops"), false, Identity.Kind.USER);

    private static final Map<String, WorkflowDefinition> WORKFLOWS = new LinkedHashMap<>();
    private static final Map<String, String> YAML = new LinkedHashMap<>();
    private static final List<ProviderDefinition> PROVIDERS = new ArrayList<>();
    private static final List<ResourceTypeDefinition> TYPES = new ArrayList<>();

    static {
        for (var f : ContentFiles.scan(ROOT)) {
            switch (f.kind()) {
                case "Workflow" -> {
                    WorkflowDefinition d = WorkflowYaml.parse(f.yaml());
                    WORKFLOWS.put(d.name(), d);
                    YAML.put(d.name(), f.yaml());
                }
                case "Provider" -> PROVIDERS.add(ProviderYaml.parse(f.yaml()));
                case "ResourceType" -> TYPES.add(ResourceTypeDefinition.parse(f.yaml()));
                default -> {
                }
            }
        }
    }

    private SampleContent() {
    }

    public static WorkflowDefinition workflow(String name) {
        WorkflowDefinition d = WORKFLOWS.get(name);
        if (d == null) {
            throw new IllegalArgumentException("No sample workflow " + name);
        }
        return d;
    }

    public static String yaml(String name) {
        return YAML.get(name);
    }

    public static List<WorkflowDefinition> workflows() {
        return List.copyOf(WORKFLOWS.values());
    }

    public static List<ProviderDefinition> providers() {
        return List.copyOf(PROVIDERS);
    }

    public static ProviderDefinition provider(String name) {
        return PROVIDERS.stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow();
    }

    public static List<ResourceTypeDefinition> types() {
        return List.copyOf(TYPES);
    }

    /** The published child for a name and a range: the sample's own version when it satisfies the range. */
    public static Optional<WorkflowDefinition> child(String workflow, String range) {
        WorkflowDefinition d = WORKFLOWS.get(workflow);
        return d != null && Semver.parse(d.version()).satisfies(range) ? Optional.of(d) : Optional.empty();
    }

    public static SampleStepExecutor executor() {
        return new SampleStepExecutor(providers(), SampleContent::child, types(), java.time.Instant.parse("2026-10-17T10:00:00Z"));
    }

    public static JsonNode json(Object value) {
        return ExpressionEngine.json(value);
    }
}
