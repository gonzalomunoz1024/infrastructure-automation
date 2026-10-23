package com.iaf.console.state.stack.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.state.inventory.domain.Json;

/** The bundled blueprints read as the service reads them, and the mistakes the reader refuses. */
class BlueprintParserTest {

    @Test
    void theBundledBlueprintsReadAndStartInTheirWaves() {
        Blueprint web = BundledBlueprints.bundled("web-app");
        assertThat(web.waves()).containsExactly(List.of("image", "database"), List.of("web"), List.of("data"));
        assertThat(web.dependentsOf("image")).containsExactly("web");
        assertThat(web.creates()).containsEntry("vm", 1).containsEntry("volume", 1);
        assertThat(web.resources().get("image").deletionPolicy()).isEqualTo(DeletionPolicy.RETAIN);
        assertThat(web.resources().get("web").deleteInput().path("reason").asText()).contains("${teardown.reason}");

        Blueprint sandbox = BundledBlueprints.bundled("dev-sandbox");
        assertThat(sandbox.waves()).containsExactly(List.of("vm"), List.of("scratch"));
        assertThat(sandbox.parameters().get("image").required()).isTrue();
        assertThat(sandbox.parameters().get("size").required()).isFalse();
    }

    @Test
    void theReaderRefusesWhatCannotRun() {
        refused(d -> resource(d, "vm").putArray("dependsOn").add("scratch"), "a cycle");
        refused(d -> resource(d, "vm").withObjectProperty("input").put("name", "${params.nope}"), "${params.nope} names no parameter");
        refused(d -> resource(d, "vm").withObjectProperty("input").put("name", "${teardown.reason}"), "only known to deleteInput");
        refused(d -> resource(d, "scratch").withObjectProperty("deleteInput").put("reason", "x"), "deleteInput is for a resource teardown deletes");
        refused(d -> resource(d, "vm").putArray("dependsOn").add("ghost"), "dependsOn ghost names no resource");
        refused(d -> resource(d, "vm").withObjectProperty("deleteInput").put("reason", "${resources.scratch.name}"), "not resources");
        refused(d -> resource(d, "vm").put("deletionPolicy", "orphan"), "deletionPolicy is delete or retain");
        refused(d -> d.put("kind", "Workflow"), "kind Blueprint");
    }

    @Test
    void theParametersAreCheckedWithTheirDefaults() {
        Blueprint sandbox = BundledBlueprints.bundled("dev-sandbox");
        ObjectNode given = ParameterCheck.check(sandbox, Json.object().put("image", "img-1"));
        assertThat(given.toString()).isEqualTo("{\"image\":\"img-1\",\"size\":\"small\",\"scratchGb\":50}");
        assertThatThrownBy(() -> ParameterCheck.check(sandbox, Json.object().put("image", "img-1").put("size", "huge")))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> ParameterCheck.check(sandbox, Json.object().put("image", "img-1").put("scratchGb", "fifty")))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> ParameterCheck.check(sandbox, Json.object().put("image", "img-1").put("color", "red")))
                .isInstanceOf(InvalidInputException.class);
    }

    @Test
    void aWholeReferenceKeepsItsTypeAndTextBecomesAString() {
        Map<String, JsonNode> values = Map.of("params.gb", Json.MAPPER.getNodeFactory().numberNode(50), "stack.name", TextNode.valueOf("sbx"));
        References.Resolver resolver = expression -> {
            JsonNode v = values.get(expression);
            if (v == null) {
                throw new References.Unresolved(expression, "unknown");
            }
            return v;
        };
        ObjectNode input = Json.object().put("sizeGb", "${params.gb}").put("name", "${stack.name}-vm").put("label", "${stack.name} has ${params.gb} GB");
        assertThat(References.resolve(input, resolver).toString()).isEqualTo("{\"sizeGb\":50,\"name\":\"sbx-vm\",\"label\":\"sbx has 50 GB\"}");
        assertThat(References.partial(Json.object().put("vmId", "${resources.vm.registryId}").put("name", "${stack.name}"), resolver).toString())
                .isEqualTo("{\"vmId\":\"${resources.vm.registryId}\",\"name\":\"sbx\"}");
    }

    private static ObjectNode resource(ObjectNode doc, String id) {
        return (ObjectNode) doc.withObjectProperty("spec").withObjectProperty("resources").get(id);
    }

    private static void refused(Consumer<ObjectNode> mistake, String says) {
        ObjectNode doc = (ObjectNode) BundledBlueprints.document("dev-sandbox").deepCopy();
        mistake.accept(doc);
        assertThatThrownBy(() -> BlueprintParser.parse(doc)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(says);
    }
}
