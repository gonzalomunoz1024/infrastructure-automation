package com.iaf.platform.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.iaf.platform.catalog.SampleCatalog;
import com.iaf.platform.catalog.domain.McpTools.Kind;
import com.iaf.platform.catalog.domain.McpTools.Tool;

class McpToolsTest {

    static final List<Tool> TOOLS = McpTools.of(SampleCatalog.workflows(), SampleCatalog.types());

    @Test
    void oneToolAndOneBatchToolPerExposedWorkflow() {
        List<String> names = TOOLS.stream().map(Tool::name).toList();
        assertThat(names).contains("vm_restart", "vm_restart_batch", "vm_create", "vm_delete", "mongodb_cluster_upgrade",
                "mongodb_cluster_upgrade_batch", "image_build", "environment_create");
        Tool restart = McpTools.find(TOOLS, "vm_restart").orElseThrow();
        assertThat(restart.kind()).isEqualTo(Kind.INVOKE);
        assertThat(restart.title()).isEqualTo("VM restart");
        assertThat(restart.description()).startsWith("Restart a virtual machine");
        assertThat(restart.inputSchema().path("required").get(0).asText()).isEqualTo("vmId");
        Tool batch = McpTools.find(TOOLS, "vm_restart_batch").orElseThrow();
        assertThat(batch.kind()).isEqualTo(Kind.BATCH);
        assertThat(batch.inputSchema().path("properties").path("items").path("items").path("properties").has("vmId")).isTrue();
        assertThat(batch.inputSchema().path("properties").path("items").path("maxItems").asInt()).isEqualTo(500);
        assertThat(batch.inputSchema().path("properties").has("defaults")).isTrue();
        assertThat(batch.inputSchema().path("properties").has("rollout")).isTrue();
        Tool delete = McpTools.find(TOOLS, "vm_delete").orElseThrow();
        assertThat(delete.annotations().path("destructiveHint").asBoolean()).isTrue();
    }

    @Test
    void theToolNamesOfAWorkflowAreTheOnesTheListGivesIt() {
        for (var w : SampleCatalog.workflows()) {
            List<String> listed = TOOLS.stream().filter(t -> t.workflow() != null && t.workflow().name().equals(w.name())).map(Tool::name).toList();
            assertThat(McpTools.toolNames(w.definition())).as(w.name()).isEqualTo(listed);
        }
        assertThat(McpTools.toolNames(SampleCatalog.workflows().stream().filter(w -> w.name().equals("vm-restart")).findFirst().orElseThrow().definition()))
                .containsExactly("vm_restart", "vm_restart_batch");
        assertThat(McpTools.toolNames(null)).isEmpty();
    }

    @Test
    void aToolInputThatCanBeBuiltFirstOffersTheCreateFormInOneCallAndInABatch() {
        Tool create = McpTools.find(TOOLS, "vm_create").orElseThrow();
        var image = create.inputSchema().path("properties").path("image");
        assertThat(image.path("anyOf")).hasSize(2);
        assertThat(image.path("anyOf").get(1).path("properties").path("create").path("properties").has("os")).isTrue();
        Tool batch = McpTools.find(TOOLS, "vm_create_batch").orElseThrow();
        assertThat(batch.inputSchema().path("properties").path("items").path("items").path("properties").path("image").path("anyOf")).hasSize(2);
        Tool restart = McpTools.find(TOOLS, "vm_restart").orElseThrow();
        assertThat(restart.inputSchema().path("properties").path("vmId").has("anyOf")).as("vmId cannot be built").isFalse();
    }

    @Test
    void readToolsPerResourceTypeAndTheFixedOnes() {
        List<String> names = TOOLS.stream().map(Tool::name).toList();
        assertThat(names).contains("list_images", "get_images", "list_vms", "get_vms", "list_databases", "list_environments", "list_volumes",
                "get_job_status", "cancel_job", "draft_workflow");
        Tool list = McpTools.find(TOOLS, "list_images").orElseThrow();
        assertThat(list.kind()).isEqualTo(Kind.LIST_RESOURCES);
        assertThat(list.type().name()).isEqualTo("image");
        assertThat(list.inputSchema().path("properties").path("status").path("enum")).extracting(n -> n.asText()).contains("ready", "deprecated");
        assertThat(list.annotations().path("readOnlyHint").asBoolean()).isTrue();
        Tool get = McpTools.find(TOOLS, "get_images").orElseThrow();
        assertThat(get.inputSchema().path("required").get(0).asText()).isEqualTo("id");
        assertThat(McpTools.find(TOOLS, "get_job_status").orElseThrow().inputSchema().path("required").get(0).asText()).isEqualTo("jobId");
        assertThat(McpTools.find(TOOLS, "draft_workflow").orElseThrow().inputSchema().path("required").get(0).asText()).isEqualTo("description");
        assertThat(names).doesNotHaveDuplicates();
    }

    @Test
    void toolJsonHasTheMcpShape() {
        var json = McpTools.find(TOOLS, "vm_restart").orElseThrow().toJson();
        assertThat(json.fieldNames()).toIterable().contains("name", "title", "description", "inputSchema", "annotations");
        assertThat(json.path("inputSchema").path("type").asText()).isEqualTo("object");
    }
}
