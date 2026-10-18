package com.iaf.platform.workflows.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.common.content.ContentFiles;
import com.iaf.platform.common.domain.Problem;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.providers.domain.ProviderYaml;
import com.iaf.platform.resources.domain.ResourceTypeDefinition;

/** The repo's own sample content must pass the checks: it is what the demo and the docs show. */
class SampleContentTest {

    static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    /** The repo's content as the catalog: providers, every workflow as published, resource types with their schemas. */
    static CheckContext context() {
        return context(null);
    }

    /** The same catalog and the vault's credential owners by name (null: the vault was not read). */
    static CheckContext context(Map<String, String> credentialOwners) {
        Map<String, ProviderDefinition> providers = new HashMap<>();
        List<WorkflowDefinition> workflows = new ArrayList<>();
        Map<String, JsonNode> types = new HashMap<>();
        for (var f : ContentFiles.scan(ROOT)) {
            switch (f.kind()) {
                case "Provider" -> {
                    ProviderDefinition p = ProviderYaml.parse(f.yaml());
                    providers.put(p.name(), p);
                }
                case "Workflow" -> workflows.add(WorkflowYaml.parse(f.yaml()));
                case "ResourceType" -> {
                    ResourceTypeDefinition t = ResourceTypeDefinition.parse(f.yaml());
                    types.put(t.name(), t.schema());
                }
                default -> {
                }
            }
        }
        return CheckContext.of(providers, workflows, types, credentialOwners);
    }

    @Test
    void everySampleWorkflowPassesTheChecks() {
        CheckContext ctx = context();
        List<ContentFiles.ContentFile> files = ContentFiles.ofKind(ROOT, "Workflow");
        assertThat(files).hasSizeGreaterThanOrEqualTo(9);
        StringBuilder report = new StringBuilder();
        for (var f : files) {
            List<Problem> problems = WorkflowChecks.check(f.yaml(), ctx);
            for (Problem p : problems) {
                report.append(f.relative(ROOT)).append(": ").append(p.level().json()).append(' ').append(p.code())
                        .append(p.step() == null ? "" : " @" + p.step()).append(p.field() == null ? "" : "." + p.field())
                        .append(" - ").append(p.message()).append('\n');
            }
        }
        assertThat(report.toString()).as("problems in sample content").isEmpty();
    }

    @Test
    void everySampleRoundTripsThroughTheCanonicalWriter() {
        for (var f : ContentFiles.ofKind(ROOT, "Workflow")) {
            WorkflowDefinition d = WorkflowYaml.parse(f.yaml());
            String yaml = WorkflowYaml.toYaml(d);
            WorkflowDefinition again = WorkflowYaml.parse(yaml);
            assertThat(WorkflowYaml.toYaml(again)).as(f.relative(ROOT)).isEqualTo(yaml);
            assertThat(again.allSteps()).hasSameSizeAs(d.allSteps());
            assertThat(WorkflowYaml.contentHash(again)).isEqualTo(WorkflowYaml.contentHash(d));
        }
        for (var f : ContentFiles.ofKind(ROOT, "Provider")) {
            ProviderDefinition p = ProviderYaml.parse(f.yaml());
            ProviderDefinition again = ProviderYaml.parse(ProviderYaml.toYaml(p));
            assertThat(again.actions()).hasSameSizeAs(p.actions());
            assertThat(ProviderYaml.toYaml(again)).isEqualTo(ProviderYaml.toYaml(p));
        }
    }
}
