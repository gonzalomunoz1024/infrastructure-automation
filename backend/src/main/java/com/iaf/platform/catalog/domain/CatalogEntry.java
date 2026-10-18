package com.iaf.platform.catalog.domain;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.workflows.domain.CreateRequests;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowDefinition.BatchPolicy;
import com.iaf.platform.workflows.domain.WorkflowDefinition.Deprecation;
import com.iaf.platform.workflows.domain.WorkflowRecord;

/**
 * One published workflow as the catalog shows it (docs/api.md, {@code CatalogEntry}). A deprecated
 * operation carries its {@code deprecated} block and a description that starts with "Deprecated:".
 */
public record CatalogEntry(
        String workflow,
        String title,
        String noun,
        String verb,
        String persona,
        String method,
        String path,
        String batchPath,
        String previewPath,
        JsonNode input,
        String mcpTool,
        String owner,
        String version,
        String description,
        BatchPolicy batch,
        Deprecation deprecated) {

    public static CatalogEntry of(WorkflowRecord record) {
        return of(record, List.of());
    }

    /** @param published every published workflow, for the create form of an {@code orCreateWith} input */
    public static CatalogEntry of(WorkflowRecord record, List<WorkflowRecord> published) {
        WorkflowDefinition d = record.definition();
        String path = d.path();
        return new CatalogEntry(
                d.name(),
                d.title(),
                d.noun(),
                d.verb(),
                d.metadata().persona(),
                d.method(),
                path,
                d.batchPath(),
                d.previewEnabled() ? path + RouteTable.PREVIEW : null,
                input(d, published),
                d.mcpExposed() ? d.mcpTool() : null,
                d.metadata().owner(),
                record.version() == null ? d.version() : record.version(),
                ApiDeprecations.describe(description(d), d.deprecation()),
                d.batchPolicy(),
                d.deprecation());
    }

    /**
     * The input as callers see it: an {@code orCreateWith} input takes the id of a resource or
     * {@code { create: <the producer's published input> }} to have one built first.
     */
    public static JsonNode input(WorkflowDefinition d, List<WorkflowRecord> published) {
        return CreateRequests.withCreateForms(d.inputSchema(), name -> producerInput(published, name));
    }

    private static Optional<JsonNode> producerInput(List<WorkflowRecord> published, String name) {
        return (published == null ? List.<WorkflowRecord>of() : published).stream()
                .filter(r -> r.definition() != null && name.equals(r.definition().name()))
                .findFirst()
                .map(r -> r.definition().inputSchema());
    }

    /** The MCP description when there is one, else the file's description. */
    public static String description(WorkflowDefinition d) {
        if (d.mcp() != null && d.mcp().description() != null && !d.mcp().description().isBlank()) {
            return d.mcp().description();
        }
        return d.metadata().description();
    }
}
