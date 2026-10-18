package com.iaf.platform.catalog;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.iaf.platform.common.content.ContentFiles;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.resources.domain.ResourceTypeDefinition;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowRecord;
import com.iaf.platform.workflows.domain.WorkflowStatus;
import com.iaf.platform.workflows.domain.WorkflowYaml;

/** The repo's sample content as published records, the way the catalog sees it. */
public final class SampleCatalog {

    public static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    public static final Identity ADMIN = new Identity("gonzalo", "Gonzalo", "gonzalo@example.com",
            List.of("team-compute", "team-platform"), List.of("ops", "sre", "admin"), true, Identity.Kind.USER);
    public static final Identity OPS = new Identity("mateo", "Mateo", "mateo@example.com", List.of("team-compute"), List.of("ops"), false, Identity.Kind.USER);
    public static final Identity DEVELOPER = new Identity("dev", "Dev", null, List.of("team-web"), List.of("developer"), false, Identity.Kind.USER);
    public static final Identity GUEST = new Identity("guest", "Guest", null, List.of(), List.of("user"), false, Identity.Kind.USER);
    public static final Identity AGENT = new Identity("sa_agent", "ops-agent", null, List.of("team-compute"), List.of("ops"), false, Identity.Kind.SERVICE);

    private SampleCatalog() {
    }

    public static List<WorkflowRecord> workflows() {
        return ContentFiles.ofKind(ROOT, WorkflowDefinition.KIND).stream()
                .map(f -> WorkflowRecord.of(WorkflowYaml.parse(f.yaml()), WorkflowStatus.PUBLISHED, f.relative(ROOT), Instant.parse("2026-01-01T00:00:00Z"), "seed"))
                .toList();
    }

    public static WorkflowRecord workflow(String name) {
        return workflows().stream().filter(w -> w.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no sample workflow " + name));
    }

    public static List<ResourceTypeDefinition> types() {
        return ContentFiles.ofKind(ROOT, ResourceTypeDefinition.KIND).stream().map(f -> ResourceTypeDefinition.parse(f.yaml())).toList();
    }

    public static ResourceTypeDefinition type(String name) {
        return types().stream().filter(t -> t.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no sample resource type " + name));
    }

    /** The JSON mapper as Spring Boot configures it: ISO dates, nulls left out. */
    public static ObjectMapper json() {
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .defaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
                .build();
    }
}
