package com.iaf.platform.catalog.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowDefinition.BatchPolicy;
import com.iaf.platform.workflows.domain.WorkflowRecord;

/** One published workflow as the catalog shows it (docs/api.md, {@code CatalogEntry}). */
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
        BatchPolicy batch) {

    public static CatalogEntry of(WorkflowRecord record) {
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
                path + RouteTable.BATCH,
                d.previewEnabled() ? path + RouteTable.PREVIEW : null,
                d.inputSchema(),
                d.mcpExposed() ? d.mcpTool() : null,
                d.metadata().owner(),
                record.version() == null ? d.version() : record.version(),
                description(d),
                d.batchPolicy());
    }

    /** The MCP description when there is one, else the file's description. */
    public static String description(WorkflowDefinition d) {
        if (d.mcp() != null && d.mcp().description() != null && !d.mcp().description().isBlank()) {
            return d.mcp().description();
        }
        return d.metadata().description();
    }
}
