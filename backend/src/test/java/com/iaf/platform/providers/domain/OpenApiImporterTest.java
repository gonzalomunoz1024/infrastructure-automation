package com.iaf.platform.providers.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.common.domain.Effect;
import com.iaf.platform.providers.domain.SpecImport.Operation;
import com.iaf.platform.workflows.domain.WorkflowYaml;

class OpenApiImporterTest {

    static String raw() {
        try (InputStream in = OpenApiImporterTest.class.getResourceAsStream("/openapi/machines-api.yaml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static Map<String, Operation> operations(SpecImport spec) {
        return spec.operations().stream().collect(Collectors.toMap(Operation::operationId, Function.identity()));
    }

    @Test
    void readsTitleVersionServersAuthAndHash() {
        String raw = raw();
        SpecImport spec = OpenApiImporter.parse(raw);
        assertThat(spec.title()).isEqualTo("Machines API");
        assertThat(spec.version()).isEqualTo("2.4.0");
        assertThat(spec.servers()).containsExactly("https://machines.example.com/api", "https://machines-dev.example.com/api");
        assertThat(spec.auth().type()).isEqualTo("bearer");
        assertThat(spec.sha256()).isEqualTo(WorkflowYaml.sha256(raw)).hasSize(64);
    }

    @Test
    void everyOperationIsListedWithAnIdGeneratedWhenMissing() {
        SpecImport spec = OpenApiImporter.parse(raw());
        assertThat(spec.operations()).extracting(Operation::operationId).containsExactly(
                "listMachines", "createMachine", "getMachine", "deleteMachine", "patch_machines_machineId",
                "restartMachine", "post_machines_machineId_logs", "terminateMachine");
        Operation patch = operations(spec).get("patch_machines_machineId");
        assertThat(patch.method()).isEqualTo("PATCH");
        assertThat(patch.path()).isEqualTo("/machines/{machineId}");
        assertThat(patch.summary()).isEqualTo("Update a machine");
    }

    @Test
    void effectsAndRetrySafetyAreInferred() {
        Map<String, Operation> ops = operations(OpenApiImporter.parse(raw()));
        assertThat(ops.get("listMachines").effect()).isEqualTo(Effect.READ);
        assertThat(ops.get("listMachines").retry()).isEqualTo("safe");
        assertThat(ops.get("createMachine").effect()).isEqualTo(Effect.CREATE);
        assertThat(ops.get("createMachine").retry()).isEqualTo("unsafe");
        assertThat(ops.get("getMachine").effect()).isEqualTo(Effect.READ);
        assertThat(ops.get("deleteMachine").effect()).isEqualTo(Effect.DELETE);
        assertThat(ops.get("deleteMachine").retry()).isEqualTo("safe");
        assertThat(ops.get("patch_machines_machineId").effect()).isEqualTo(Effect.CHANGE);
        assertThat(ops.get("patch_machines_machineId").retry()).isEqualTo("unsafe");
        assertThat(ops.get("restartMachine").effect()).isEqualTo(Effect.CHANGE);
        assertThat(ops.get("post_machines_machineId_logs").effect()).isEqualTo(Effect.READ);
        assertThat(ops.get("terminateMachine").effect()).isEqualTo(Effect.DELETE);
    }

    @Test
    void pathAndQueryParametersBecomeInputs() {
        Map<String, Operation> ops = operations(OpenApiImporter.parse(raw()));
        JsonNode list = ops.get("listMachines").input();
        assertThat(list.path("type").asText()).isEqualTo("object");
        assertThat(list.path("properties").path("environment").path("x-in").asText()).isEqualTo("query");
        assertThat(list.path("properties").path("limit").path("type").asText()).isEqualTo("integer");
        assertThat(list.path("required")).extracting(JsonNode::asText).containsExactly("limit");
        JsonNode get = ops.get("getMachine").input();
        assertThat(get.path("properties").path("machineId").path("x-in").asText()).isEqualTo("path");
        assertThat(get.path("properties").path("machineId").path("description").asText()).isEqualTo("The machine id");
        assertThat(get.path("required")).extracting(JsonNode::asText).containsExactly("machineId");
        JsonNode logs = ops.get("post_machines_machineId_logs").input();
        assertThat(logs.path("properties").has("machineId")).isTrue();
        assertThat(logs.path("properties").path("q").path("x-in").asText()).isEqualTo("query");
    }

    @Test
    void theRequestBodyIsMergedIntoTheInputWithAllOfFlattened() {
        JsonNode input = operations(OpenApiImporter.parse(raw())).get("createMachine").input();
        assertThat(input.path("properties").path("name").path("type").asText()).isEqualTo("string");
        assertThat(input.path("properties").path("cpu").path("default").asInt()).isEqualTo(2);
        assertThat(input.path("properties").path("templateId").path("type").asText()).isEqualTo("string");
        assertThat(input.path("required")).extracting(JsonNode::asText).containsExactly("name", "templateId");
        assertThat(input.has("allOf")).isFalse();
    }

    @Test
    void responsesAreResolvedThroughReferencesAndCyclesAreCut() {
        Map<String, Operation> ops = operations(OpenApiImporter.parse(raw()));
        JsonNode list = ops.get("listMachines").output();
        JsonNode machine = list.path("properties").path("items").path("items");
        assertThat(machine.path("properties").path("disks").path("items").path("properties").path("sizeGb").path("type").asText()).isEqualTo("integer");
        assertThat(machine.path("required")).extracting(JsonNode::asText).containsExactly("id", "name");
        JsonNode owner = machine.path("properties").path("owner");
        assertThat(owner.path("properties").path("parent").path("type").asText()).isEqualTo("object");
        assertThat(list.toString()).doesNotContain("$ref");
        JsonNode get = ops.get("getMachine").output();
        assertThat(get.path("properties").path("powerState").path("enum")).hasSize(2);
        assertThat(ops.get("restartMachine").output().path("properties").path("task").path("type").asText()).isEqualTo("string");
    }

    @Test
    void anOperationWithoutContentHasNoOutput() {
        Map<String, Operation> ops = operations(OpenApiImporter.parse(raw()));
        assertThat(ops.get("deleteMachine").output()).isNull();
        assertThat(ops.get("post_machines_machineId_logs").output()).isNull();
    }

    @Test
    void generatedOperationIdsAreFoundAgain() {
        JsonNode doc = OpenApiImporter.read(raw());
        assertThat(OpenApiImporter.find(doc, "patch_machines_machineId")).isPresent();
        assertThat(OpenApiImporter.find(doc, "patch_machines_machineId").orElseThrow().method()).isEqualTo("PATCH");
        assertThat(OpenApiImporter.find(doc, "getMachine").orElseThrow().parameterNames("path")).containsExactly("machineId");
        assertThat(OpenApiImporter.find(doc, "listMachines").orElseThrow().parameterNames("query")).containsExactly("environment", "limit");
        assertThat(OpenApiImporter.find(doc, "nope")).isEmpty();
        assertThat(OpenApiImporter.operationId("POST", "/images/{imageId}:scan")).isEqualTo("post_images_imageId_scan");
        assertThat(OpenApiImporter.index(doc)).containsKeys("listMachines", "patch_machines_machineId");
    }

    @Test
    void readsJsonAsWellAsYaml() {
        String json = """
                {"openapi":"3.0.3","info":{"title":"Tiny","version":"1"},"paths":{"/ping":{"get":{"responses":{"200":{"description":"ok"}}}}}}
                """;
        SpecImport spec = OpenApiImporter.parse(json);
        assertThat(spec.title()).isEqualTo("Tiny");
        assertThat(spec.auth().type()).isEqualTo("none");
        assertThat(spec.operations()).extracting(Operation::operationId).containsExactly("get_ping");
    }

    @Test
    void apiKeyHeadersAndBasicAuthAreRecognised() {
        String yaml = """
                openapi: 3.1.0
                info: { title: Keyed, version: 1 }
                paths: { /x: { get: { responses: { '200': { description: ok } } } } }
                components:
                  securitySchemes:
                    key: { type: apiKey, in: header, name: X-Api-Key }
                """;
        SpecImport keyed = OpenApiImporter.parse(yaml);
        assertThat(keyed.auth().type()).isEqualTo("header");
        assertThat(keyed.auth().where()).isEqualTo("X-Api-Key");
        SpecImport basic = OpenApiImporter.parse(yaml.replace("key: { type: apiKey, in: header, name: X-Api-Key }", "basic: { type: http, scheme: basic }"));
        assertThat(basic.auth().type()).isEqualTo("basic");
    }

    @Test
    void rejectsSwaggerTwoAndNonSpecs() {
        assertThatThrownBy(() -> OpenApiImporter.parse("swagger: '2.0'\ninfo: { title: Old }\npaths: {}\n"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Swagger 2.0");
        assertThatThrownBy(() -> OpenApiImporter.parse("hello: world\n")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("openapi");
        assertThatThrownBy(() -> OpenApiImporter.parse("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpenApiImporter.parse("openapi: 3.1.0\ninfo: {}\n")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("paths");
        assertThatThrownBy(() -> OpenApiImporter.parse("{ not json")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aKeyWrittenTwiceIsRefusedLikeEveryOtherFile() {
        String twice = """
                openapi: 3.1.0
                info: { title: Twice, version: 1 }
                paths: { /x: { get: { responses: { '200': { description: ok } } } } }
                paths: { /y: { delete: { responses: { '204': { description: gone } } } } }
                """;
        assertThatThrownBy(() -> OpenApiImporter.read(twice)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate field");
        assertThatThrownBy(() -> OpenApiImporter.parse("{\"openapi\":\"3.0.3\",\"openapi\":\"3.1.0\",\"info\":{},\"paths\":{}}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate field");
    }

    @Test
    void aReferenceWithAnIndexTooLongForAnIntPointsNowhereInsteadOfThrowing() {
        String yaml = """
                openapi: 3.1.0
                info: { title: Crafted, version: 1 }
                paths:
                  /x:
                    get:
                      operationId: getX
                      responses:
                        '200':
                          description: ok
                          content: { application/json: { schema: { $ref: '#/components/list/99999999999' } } }
                components:
                  list: [ { type: object, properties: { id: { type: string } } } ]
                """;
        JsonNode doc = OpenApiImporter.read(yaml);
        assertThat(OpenApiImporter.pointer(doc, "#/components/list/99999999999")).isNull();
        assertThat(OpenApiImporter.pointer(doc, "#/components/list/0").path("type").asText()).isEqualTo("object");
        Operation x = operations(OpenApiImporter.parse(yaml)).get("getX");
        assertThat(x.output().path("description").asText()).contains("Unresolved reference");
    }
}
