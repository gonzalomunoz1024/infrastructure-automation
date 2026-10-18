package com.iaf.platform.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.catalog.SampleCatalog;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowDefinition.Deprecation;
import com.iaf.platform.workflows.domain.WorkflowRecord;
import com.iaf.platform.workflows.domain.WorkflowStatus;
import com.iaf.platform.workflows.domain.WorkflowYaml;

/** {@code api.deprecated} and {@code /api/v<n>/} on the generated surface: headers, catalog, OpenAPI and MCP. */
public class ApiDeprecationsTest {

    static final Deprecation DEPRECATED = new Deprecation("2026-11-01", "2027-05-01", "/api/v2/vms/{vmId}:restart", null);

    /** The sample vm-restart moved to {@code /api/v2/} and deprecated, as a published record. */
    public static WorkflowRecord deprecatedRestart() {
        String yaml;
        try {
            yaml = Files.readString(SampleCatalog.ROOT.resolve("workflows/team-compute/vm-restart.yaml"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String moved = yaml.replace("  path: /api/v1/vms/{vmId}:restart",
                "  path: /api/v2/machines/{vmId}:restart\n  deprecated: { since: 2026-11-01, sunset: 2027-05-01, replacement: \"/api/v3/machines/{vmId}:reboot\" }");
        assertThat(moved).isNotEqualTo(yaml);
        return WorkflowRecord.of(WorkflowYaml.parse(moved), WorkflowStatus.PUBLISHED, "workflows/team-compute/vm-restart.yaml",
                Instant.parse("2026-01-01T00:00:00Z"), "seed");
    }

    @Test
    void headersCarryTheDeprecationDateTheSunsetAndTheSuccessorOfThisCall() {
        Map<String, String> h = ApiDeprecations.headers(DEPRECATED, Map.of("vmId", "vm 7"));
        assertThat(h).containsEntry("Deprecation", "@" + Instant.parse("2026-11-01T00:00:00Z").getEpochSecond())
                .containsEntry("Sunset", "Sat, 01 May 2027 00:00:00 GMT")
                .containsEntry("Link", "</api/v2/vms/vm%207:restart>; rel=\"successor-version\"");
        assertThat(ApiDeprecations.headers(new Deprecation("2026-11-01T12:30:00+02:00", null, null, null), Map.of()))
                .containsOnlyKeys("Deprecation")
                .containsEntry("Deprecation", "@" + Instant.parse("2026-11-01T10:30:00Z").getEpochSecond());
        assertThat(ApiDeprecations.headers(null, Map.of())).isEmpty();
    }

    @Test
    void theNoteIsTheMessageOrTheSunsetAndReplacement() {
        assertThat(ApiDeprecations.describe("Restart a VM.", DEPRECATED))
                .isEqualTo("Deprecated: it will be removed on 2027-05-01; use /api/v2/vms/{vmId}:restart instead. Restart a VM.");
        assertThat(ApiDeprecations.describe("Restart a VM.", new Deprecation("2026-11-01", "2027-05-01", null, "Use vm_reboot")))
                .isEqualTo("Deprecated: Use vm_reboot. Restart a VM.");
        assertThat(ApiDeprecations.describe(null, new Deprecation("2026-11-01", null, null, null))).isEqualTo("Deprecated: it will be removed.");
        assertThat(ApiDeprecations.describe("Restart a VM.", null)).isEqualTo("Restart a VM.");
    }

    @Test
    void readsDatesAndDateTimes() {
        assertThat(ApiDeprecations.instant("2027-05-01")).isEqualTo(Instant.parse("2027-05-01T00:00:00Z"));
        assertThat(ApiDeprecations.instant("2027-05-01T08:00:00Z")).isEqualTo(Instant.parse("2027-05-01T08:00:00Z"));
        assertThat(ApiDeprecations.instant("May 2027")).isNull();
        assertThat(ApiDeprecations.instant(null)).isNull();
    }

    @Test
    void theBlockSurvivesTheYamlRoundTrip() {
        WorkflowDefinition d = deprecatedRestart().definition();
        assertThat(d.deprecation()).isEqualTo(new Deprecation("2026-11-01", "2027-05-01", "/api/v3/machines/{vmId}:reboot", null));
        assertThat(WorkflowYaml.parse(WorkflowYaml.toYaml(d)).deprecation()).isEqualTo(d.deprecation());
        assertThat(WorkflowYaml.toYaml(d)).contains("deprecated:");
        assertThat(WorkflowYaml.parse(WorkflowYaml.toYaml(d).replace("deprecated:", "unused:")).deprecation()).isNull();
    }

    @Test
    void routesUnderAnyApiVersionAreGeneratedButPlatformNounsNeverAre() {
        assertThat(ReservedNouns.isGenerated("/api/v2/machines/vm-1:restart")).isTrue();
        assertThat(ReservedNouns.isGenerated("/api/v10/machines")).isTrue();
        assertThat(ReservedNouns.isGenerated("/api/v2/jobs")).isFalse();
        assertThat(ReservedNouns.isGenerated("/api/v1/notifications")).isFalse();
        assertThat(ReservedNouns.isGenerated("/api/v0/machines")).isFalse();
        assertThat(ReservedNouns.isGenerated("/api/vx/machines")).isFalse();
        assertThat(ReservedNouns.firstSegment("/api/v2/machines/{vmId}:restart")).isEqualTo("machines");

        RouteTable table = RouteTable.of(List.of(deprecatedRestart()), List.of());
        assertThat(table.match("POST", "/api/v2/machines/vm-1:restart")).hasValueSatisfying(m -> assertThat(m.params()).containsEntry("vmId", "vm-1"));
        assertThat(table.match("POST", "/api/v2/machines:restart:batch")).isPresent();
        assertThat(table.match("POST", "/api/v1/machines/vm-1:restart")).isEmpty();
    }

    @Test
    void theCatalogOpenApiAndMcpMarkTheOperationDeprecated() {
        WorkflowRecord w = deprecatedRestart();
        CatalogEntry entry = CatalogEntry.of(w);
        assertThat(entry.path()).isEqualTo("/api/v2/machines/{vmId}:restart");
        assertThat(entry.deprecated().sunset()).isEqualTo("2027-05-01");
        assertThat(entry.description()).startsWith("Deprecated: it will be removed on 2027-05-01; use /api/v3/machines/{vmId}:reboot instead.");

        JsonNode doc = OpenApiDocument.build(List.of(w), List.of());
        JsonNode op = doc.path("paths").path("/api/v2/machines/{vmId}:restart").path("post");
        assertThat(op.path("deprecated").asBoolean()).isTrue();
        assertThat(op.path("x-sunset").asText()).isEqualTo("2027-05-01");
        assertThat(op.path("x-replacement").asText()).isEqualTo("/api/v3/machines/{vmId}:reboot");
        assertThat(op.path("description").asText()).startsWith("Deprecated:");
        assertThat(doc.path("paths").path("/api/v2/machines:restart:batch").path("post").path("deprecated").asBoolean()).isTrue();
        assertThat(doc.path("paths").path("/api/v2/machines/{vmId}:restart:preview").path("post").path("deprecated").asBoolean()).isTrue();

        List<McpTools.Tool> tools = McpTools.of(List.of(w), List.of());
        assertThat(McpTools.find(tools, "vm_restart").orElseThrow().description()).startsWith("Deprecated: ");
        assertThat(McpTools.find(tools, "vm_restart_batch").orElseThrow().description()).startsWith("Deprecated: ");

        WorkflowRecord current = SampleCatalog.workflow("vm-restart");
        assertThat(CatalogEntry.of(current).deprecated()).isNull();
        assertThat(OpenApiDocument.build(List.of(current), List.of()).path("paths").path("/api/v1/vms/{vmId}:restart").path("post").has("deprecated")).isFalse();
    }
}
