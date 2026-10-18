package com.iaf.platform.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.catalog.SampleCatalog;

/** The OpenAPI document generated over the sample content. */
class OpenApiDocumentTest {

    static final ObjectNode DOC = OpenApiDocument.build(SampleCatalog.workflows(), SampleCatalog.types());

    @Test
    void isValidJsonWithTheExpectedPaths() throws Exception {
        ObjectMapper json = new ObjectMapper();
        JsonNode again = json.readTree(json.writeValueAsString(DOC));
        assertThat(again.path("openapi").asText()).isEqualTo("3.1.0");
        assertThat(again.path("info").path("title").asText()).isNotBlank();
        assertThat(again.path("info").path("version").asText()).isEqualTo(PlatformInfo.VERSION);
        assertThat(again.path("servers").isArray()).isTrue();
        List<String> paths = new ArrayList<>();
        again.path("paths").fieldNames().forEachRemaining(paths::add);
        assertThat(paths).contains(
                "/api/v1/vms/{vmId}:restart", "/api/v1/vms:restart:batch", "/api/v1/vms/{vmId}:restart:preview",
                "/api/v1/vms", "/api/v1/vms/{vmId}", "/api/v1/vms:delete:batch",
                "/api/v1/images:build", "/api/v1/images:build:batch",
                "/api/v1/images", "/api/v1/images/{id}", "/api/v1/databases", "/api/v1/environments/{id}",
                "/api/v1/jobs", "/api/v1/jobs/{id}", "/api/v1/jobs/{id}/events");
        assertThat(paths).doesNotContain("/api/v1/images:build:preview", "/api/v1/vms/{vmId}:restart:batch", "/api/v1/vms/{vmId}:batch");
    }

    @Test
    void aWorkflowOperationCarriesItsIdSummaryParametersAndResponses() {
        JsonNode op = DOC.path("paths").path("/api/v1/vms/{vmId}:restart").path("post");
        assertThat(op.path("operationId").asText()).isEqualTo("vm_restart");
        assertThat(op.path("summary").asText()).isEqualTo("VM restart");
        assertThat(op.path("tags").get(0).asText()).isEqualTo("Compute");
        assertThat(op.path("x-workflow").asText()).isEqualTo("vm-restart");
        assertThat(op.path("x-mcp-tool").asText()).isEqualTo("vm_restart");
        JsonNode param = op.path("parameters").get(0);
        assertThat(param.path("name").asText()).isEqualTo("vmId");
        assertThat(param.path("in").asText()).isEqualTo("path");
        assertThat(param.path("required").asBoolean()).isTrue();
        assertThat(param.path("schema").path("type").asText()).isEqualTo("string");
        JsonNode body = op.path("requestBody").path("content").path("application/json").path("schema");
        assertThat(body.path("properties").has("vmId")).isFalse();
        assertThat(body.path("properties").has("reason")).isTrue();
        assertThat(body.has("required")).isFalse();
        assertThat(op.path("responses").path("202").path("content").path("application/json").path("schema").path("$ref").asText())
                .isEqualTo("#/components/schemas/JobAccepted");
        assertThat(op.path("responses").has("400")).isTrue();
        assertThat(op.path("responses").has("403")).isTrue();
    }

    @Test
    void createKeepsItsRequiredFieldsInTheBody() {
        JsonNode op = DOC.path("paths").path("/api/v1/vms").path("post");
        assertThat(op.path("operationId").asText()).isEqualTo("vm_create");
        assertThat(op.path("parameters")).isEmpty();
        JsonNode body = op.path("requestBody").path("content").path("application/json").path("schema");
        List<String> required = new ArrayList<>();
        body.path("required").forEach(r -> required.add(r.asText()));
        assertThat(required).containsExactly("name", "image", "size");
    }

    @Test
    void anInputThatCanBeBuiltFirstTakesAnIdOrTheProducersInputUnderCreate() {
        JsonNode body = DOC.path("paths").path("/api/v1/vms").path("post").path("requestBody").path("content").path("application/json").path("schema");
        JsonNode image = body.path("properties").path("image");
        assertThat(image.path("x-resource").path("orCreateWith").asText()).isEqualTo("build-vm-image");
        assertThat(image.path("anyOf").get(0).path("type").asText()).isEqualTo("string");
        JsonNode create = image.path("anyOf").get(1).path("properties").path("create");
        assertThat(create.path("properties").has("os")).as("build-vm-image's input").isTrue();
        assertThat(create.path("properties").has("hardening")).isTrue();
        assertThat(DOC.path("components").path("schemas").path("vm_create_input").path("properties").path("image").has("anyOf")).isTrue();
    }

    @Test
    void batchAndPreviewOperations() {
        JsonNode batch = DOC.path("paths").path("/api/v1/vms:patch:batch").path("post");
        assertThat(batch.path("operationId").asText()).isEqualTo("vm_patch_batch");
        // on the collection: no path parameter, and every row names its VM
        assertThat(batch.path("parameters")).isEmpty();
        JsonNode schema = batch.path("requestBody").path("content").path("application/json").path("schema");
        assertThat(schema.path("properties").path("items").path("items").path("properties").has("vmId")).isTrue();
        assertThat(schema.path("required").get(0).asText()).isEqualTo("items");
        assertThat(schema.path("properties").path("items").path("maxItems").asInt()).isEqualTo(1000);
        assertThat(schema.path("properties").path("items").path("items").path("properties").has("cve")).isTrue();
        assertThat(schema.path("properties").path("rollout").path("properties").path("waves").path("default")).hasSize(3);
        assertThat(batch.path("responses").path("202").path("content").path("application/json").path("schema").path("$ref").asText())
                .isEqualTo("#/components/schemas/BatchAccepted");
        assertThat(batch.path("responses").path("400").path("content").path("application/json").path("schema").path("$ref").asText())
                .isEqualTo("#/components/schemas/BatchProblemResponse");

        JsonNode preview = DOC.path("paths").path("/api/v1/databases:preview").path("post");
        assertThat(preview.path("operationId").asText()).isEqualTo("database_create_preview");
        assertThat(preview.path("responses").path("200").path("content").path("application/json").path("schema").path("$ref").asText())
                .isEqualTo("#/components/schemas/Preview");
    }

    @Test
    void resourceReadsAndSharedSchemas() {
        JsonNode list = DOC.path("paths").path("/api/v1/images").path("get");
        assertThat(list.path("operationId").asText()).isEqualTo("list_images");
        assertThat(list.path("tags").get(0).asText()).isEqualTo("Resources");
        assertThat(list.path("parameters").get(0).path("name").asText()).isEqualTo("status");
        assertThat(list.path("parameters").get(0).path("schema").path("enum")).isNotEmpty();
        JsonNode get = DOC.path("paths").path("/api/v1/images/{id}").path("get");
        assertThat(get.path("operationId").asText()).isEqualTo("get_images");
        assertThat(get.path("responses").has("404")).isTrue();
        JsonNode schemas = DOC.path("components").path("schemas");
        for (String name : List.of("JobAccepted", "BatchAccepted", "Problem", "ProblemResponse", "BatchProblemResponse",
                "Preview", "PolicyDecision", "Resource", "JobSummary", "JobDetail", "JobEvent", "ErrorResponse", "vm_restart_input")) {
            assertThat(schemas.has(name)).as(name).isTrue();
        }
        assertThat(schemas.path("JobDetail").path("allOf").get(1).path("properties").path("links").path("properties").has("temporal"))
                .as("a job links to its execution in the Temporal UI").isTrue();
        assertThat(DOC.path("tags")).extracting(t -> t.path("name").asText()).contains("Compute", "Data", "Platform", "Resources", "Jobs");
        assertThat(schemas.path("PreviewStep").path("properties").path("skipped").path("type").asText()).isEqualTo("boolean");
    }

    @Test
    void aReadWorkflowTakesItsInputFromTheQueryString() {
        String yaml = """
                apiVersion: iaf.platform/v1
                kind: Workflow
                metadata: { name: cluster-info, title: Cluster info, noun: cluster, verb: read, owner: team-data, version: 1.0.0 }
                api:
                  method: GET
                  path: /api/v1/clusters/{clusterId}
                  input:
                    type: object
                    required: [clusterId]
                    properties:
                      clusterId: { type: string }
                      verbose: { type: boolean }
                workflow:
                  do:
                    - noop:
                        set: { x: 1 }
                """;
        var record = com.iaf.platform.workflows.domain.WorkflowRecord.of(com.iaf.platform.workflows.domain.WorkflowYaml.parse(yaml),
                com.iaf.platform.workflows.domain.WorkflowStatus.PUBLISHED, "x", java.time.Instant.EPOCH, "test");
        ObjectNode doc = OpenApiDocument.build(List.of(record), List.of());
        JsonNode op = doc.path("paths").path("/api/v1/clusters/{clusterId}").path("get");
        assertThat(op.path("operationId").asText()).isEqualTo("cluster_read");
        assertThat(op.has("requestBody")).isFalse();
        assertThat(op.path("parameters")).extracting(p -> p.path("name").asText() + ":" + p.path("in").asText())
                .containsExactly("clusterId:path", "verbose:query");
    }
}
