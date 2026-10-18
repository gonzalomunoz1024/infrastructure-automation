package com.iaf.platform.runs.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.runs.RunsTestWiring;
import com.iaf.platform.runs.SampleContent;
import com.iaf.platform.runs.domain.DryRunResult;
import com.iaf.platform.workflows.domain.WorkflowDefinition;

/**
 * Every sample workflow dry-runs to its end against the samples recorded in the provider files,
 * with an input built from its own input schema: a property's first example, its default, its
 * first allowed value, or a plain value of its type. CI runs this as the dry-run check of the
 * content, so a sample that no longer runs against its recorded samples fails the build.
 */
class SampleWorkflowsDryRunTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final JsonNodeFactory F = JsonNodeFactory.instance;

    static Stream<String> samples() {
        return SampleContent.workflows().stream().map(WorkflowDefinition::name);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    void everySampleWorkflowDryRunsToItsEnd(String name) {
        WorkflowDefinition def = SampleContent.workflow(name);
        JsonNode input = inputFrom(def.inputSchema());
        RunsCommandHandler handler = new RunsTestWiring().commands(RunsTestWiring.idleEngine());

        DryRunResult r = handler.dryRun(def, input, SampleContent.GONZALO).block(Duration.ofSeconds(30));

        assertThat(r).isNotNull();
        assertThat(r.passed()).as("%s with input %s: %s", name, input, r.error()).isTrue();
        assertThat(r.steps()).as(name).isNotEmpty().extracting(DryRunResult.DryRunStep::status).doesNotContain("failed");
    }

    @Test
    void thereAreSamplesToDryRun() {
        assertThat(samples()).hasSizeGreaterThanOrEqualTo(5);
    }

    @Test
    void theInputComesFromTheSchema() throws Exception {
        JsonNode schema = JSON.readTree("""
                {"type": "object", "properties": {
                  "id": {"type": "string", "examples": ["x-7"]},
                  "size": {"type": "string", "enum": ["s", "m"], "default": "m"},
                  "tier": {"type": "string", "enum": ["a", "b"]},
                  "count": {"type": "integer", "minimum": 3},
                  "ratio": {"type": "number"},
                  "on": {"type": "boolean"},
                  "why": {"type": "string", "minLength": 20},
                  "tags": {"type": "array"},
                  "name": {"type": "string"}}}
                """);

        assertThat(inputFrom(schema)).isEqualTo(JSON.readTree("""
                {"id": "x-7", "size": "m", "tier": "a", "count": 3, "ratio": 1, "on": false,
                 "why": "sample-why sample-why", "tags": [], "name": "sample-name"}
                """));
        assertThat(inputFrom(null)).isEqualTo(F.objectNode());
    }

    /** One value per property of the input schema. */
    static ObjectNode inputFrom(JsonNode schema) {
        ObjectNode out = F.objectNode();
        if (schema == null || !schema.path("properties").isObject()) {
            return out;
        }
        Iterator<Map.Entry<String, JsonNode>> it = schema.path("properties").fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            out.set(e.getKey(), valueFor(e.getKey(), e.getValue()));
        }
        return out;
    }

    static JsonNode valueFor(String name, JsonNode p) {
        if (p.path("examples").isArray() && !p.path("examples").isEmpty()) {
            return p.path("examples").get(0);
        }
        if (p.has("default")) {
            return p.get("default");
        }
        if (p.path("enum").isArray() && !p.path("enum").isEmpty()) {
            return p.path("enum").get(0);
        }
        return switch (p.path("type").asText("string")) {
            case "integer", "number" -> F.numberNode(Math.max(1, p.path("minimum").asInt(1)));
            case "boolean" -> F.booleanNode(false);
            case "array" -> F.arrayNode();
            case "object" -> F.objectNode();
            default -> {
                String v = "sample-" + name.toLowerCase();
                int min = p.path("minLength").asInt(0);
                StringBuilder s = new StringBuilder(v);
                while (s.length() < min) {
                    s.append(' ').append(v);
                }
                yield F.textNode(s.toString());
            }
        };
    }
}
