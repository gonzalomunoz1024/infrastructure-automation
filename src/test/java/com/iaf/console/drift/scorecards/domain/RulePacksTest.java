package com.iaf.console.drift.scorecards.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.iaf.console.drift.drift.domain.Json;
import com.iaf.console.drift.drift.domain.Severity;
import com.iaf.console.drift.scorecards.adapters.content.RulePackLoader;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

/**
 * The packs built in: each is a valid {@code RulePack} of the pinned schema, and every example
 * agrees with the embedded engine (the hub's check runs the same examples through OPA).
 */
class RulePacksTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private final RulePacks packs = RulePackLoader.load(null);

    @Test
    void theBuiltInPacksLoad() {
        assertThat(packs.packs()).extracting(RulePack::name).containsExactly("compute-baseline", "data-baseline", "required-tags");
        assertThat(packs.allRules()).hasSizeGreaterThanOrEqualTo(10);
        assertThat(packs.digest()).isNotBlank();
        assertThat(packs.rule(new RuleKey("required-tags", "owner")).orElseThrow().severity()).isEqualTo(Severity.WARNING);
        assertThat(packs.pack("required-tags").orElseThrow().regoPath()).isEqualTo("console/scorecards/required_tags");
        assertThat(packs.rule(new RuleKey("compute-baseline", "prod-min-cpu")).orElseThrow().regoPackage()).isEqualTo("console.scorecards.compute_baseline.prod_min_cpu");
    }

    @Test
    void everyPackIsAValidRulePack() throws IOException {
        JsonSchema schema;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("contracts/schemas/rule-pack.schema.json")) {
            schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(new ObjectMapper().readTree(in));
        }
        Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath*:rule-packs/*.yaml");
        assertThat(files).hasSize(3);
        for (Resource file : files) {
            try (InputStream in = file.getInputStream()) {
                Set<ValidationMessage> errors = schema.validate(YAML.readTree(in));
                assertThat(errors).as(file.getFilename()).isEmpty();
            }
        }
    }

    @Test
    void everyExampleAgreesWithTheEmbeddedEngine() {
        int checked = 0;
        for (Rule rule : packs.allRules()) {
            assertThat(rule.examples()).as("%s has examples", rule.key()).isNotEmpty();
            for (Rule.Example example : rule.examples()) {
                Verdict v = rule.judge(example.input());
                String got = !v.applies() ? "not-applicable" : v.passed() ? "pass" : "fail";
                assertThat(got).as("%s on %s", rule.key(), example.input()).isEqualTo(example.expect());
                if (!v.passed() && v.applies()) {
                    assertThat(v.message()).as("%s says why", rule.key()).isNotBlank().doesNotContain("{{");
                }
                checked++;
            }
        }
        assertThat(checked).isGreaterThanOrEqualTo(30);
    }

    @Test
    void conditionsAreFalseOnAMissingValueExceptAbsence() {
        JsonNode input = Json.MAPPER.valueToTree(Map.of("resource", Map.of("tags", Map.of("owner", "team-a", "blank", ""), "cpu", 4)));
        assertThat(holds("{path: resource.tags.owner, present: true}", input)).isTrue();
        assertThat(holds("{path: resource.tags.blank, present: true}", input)).isFalse();
        assertThat(holds("{path: resource.tags.missing, present: false}", input)).isTrue();
        assertThat(holds("{path: resource.tags.missing, notEquals: x}", input)).isFalse();
        assertThat(holds("{path: resource.tags.missing, notIn: [x]}", input)).isFalse();
        assertThat(holds("{path: resource.missing, gte: 1}", input)).isFalse();
        assertThat(holds("{not: {path: resource.missing, gte: 1}}", input)).isTrue();
        assertThat(holds("{path: resource.cpu, gte: 4.0}", input)).isTrue();
        assertThat(holds("{path: resource.cpu, equals: 4.0}", input)).isTrue();
        assertThat(holds("{path: resource.cpu, gt: 4}", input)).isFalse();
        assertThat(holds("{path: resource.tags.owner, matches: 'team-.*'}", input)).isTrue();
        assertThat(holds("{path: resource.tags.owner, matches: 'team'}", input)).as("a full match").isFalse();
        assertThat(holds("{any: [{path: resource.cpu, lt: 2}, {path: resource.tags.owner, in: [team-a, team-b]}]}", input)).isTrue();
        assertThat(holds("{all: [{path: resource.cpu, lt: 2}, {path: resource.tags.owner, in: [team-a]}]}", input)).isFalse();
        assertThatThrownBy(() -> Condition.parse(yaml("{path: resource.cpu, between: [1, 2]}"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void templatesRenderInputsAndTheResourceTyped() {
        ObjectNode inputs = Json.object();
        inputs.put("targetVersion", "7.0");
        inputs.put("cpu", 4);
        JsonNode resource = Json.MAPPER.valueToTree(Map.of("environment", "prod", "tags", Map.of("owner", "team-a")));
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("version", "{{ $input.targetVersion }}");
        template.put("cpu", "{{ $input.cpu }}");
        template.put("note", "for {{ $resource.tags.owner }} in {{ $resource.environment }}");
        template.put("list", List.of("{{ $resource.environment }}"));
        JsonNode out = Templates.render(Json.MAPPER.valueToTree(template), inputs, resource);
        assertThat(out.path("version").asText()).isEqualTo("7.0");
        assertThat(out.path("cpu").isInt()).as("a lone template keeps its type").isTrue();
        assertThat(out.path("note").asText()).isEqualTo("for team-a in prod");
        assertThat(out.path("list").get(0).asText()).isEqualTo("prod");
        assertThat(Templates.templated(Json.MAPPER.valueToTree("{{ $input.x }}"))).isTrue();
    }

    @Test
    void scoresWeighBySeverity() {
        Rule critical = packs.allRules().stream().filter(r -> r.severity() == Severity.CRITICAL).findFirst().orElseThrow();
        Rule info = packs.allRules().stream().filter(r -> r.severity() == Severity.INFO).findFirst().orElseThrow();
        Map<Rule, Counts> counts = new LinkedHashMap<>();
        counts.put(critical, new Counts(1, 1));
        counts.put(info, new Counts(0, 4));
        // (10*1) / (10*2 + 1*4) = 10/24 -> 42
        assertThat(Scores.score(counts)).isEqualTo(42);
        assertThat(Scores.score(Map.of())).isNull();
        assertThat(Scores.score(new Counts(3, 1))).isEqualTo(75);
        assertThat(Scores.weight(Severity.WARNING)).isEqualTo(3);
        assertThat(Counts.ZERO.plus(-2, 1)).isEqualTo(new Counts(0, 1));
    }

    @Test
    void ruleKeysRoundTrip() {
        RuleKey key = RuleKey.parse("data-baseline/supported-version");
        assertThat(key).isEqualTo(new RuleKey("data-baseline", "supported-version"));
        assertThat(key.text()).isEqualTo("data-baseline/supported-version");
        assertThatThrownBy(() -> RuleKey.parse("no-slash")).isInstanceOf(IllegalArgumentException.class);
    }

    private static boolean holds(String condition, JsonNode input) {
        return Condition.parse(yaml(condition)).holds(input);
    }

    private static JsonNode yaml(String text) {
        try {
            return YAML.readTree(text);
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
