package com.iaf.console.drift.drift.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

class DriftRulesTest {

    private static ObjectNode json(String text) {
        try {
            return (ObjectNode) Json.MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static DriftRules.Comparison compare(String desired, String observed) {
        return DriftRules.compare(json(desired), json(observed), Set.of("owner"), List.of(), Set.of("cpu", "memoryGb"), false);
    }

    @Test
    void valuesCompareByMeaningNotSpelling() {
        assertThat(compare("{\"cpu\": 4}", "{\"cpu\": \"4\"}").drifted()).isFalse();
        assertThat(compare("{\"cpu\": 4}", "{\"cpu\": 4.0}").drifted()).isFalse();
        assertThat(compare("{\"on\": true}", "{\"on\": \"TRUE\"}").drifted()).isFalse();
        assertThat(compare("{\"name\": \"web-01\"}", "{\"name\": \" web-01 \"}").drifted()).isFalse();
        assertThat(compare("{\"zones\": [\"a\", \"b\"]}", "{\"zones\": [\"b\", \"a\"]}").drifted()).isFalse();
        assertThat(compare("{\"spec\": {\"a\": 1, \"b\": 2}}", "{\"spec\": {\"b\": 2, \"a\": 1}}").drifted()).isFalse();
        assertThat(compare("{\"at\": \"2026-10-23T10:00:00Z\"}", "{\"at\": \"2026-10-23T10:00:00.000Z\"}").drifted()).isFalse();
    }

    @Test
    void aChangedValueIsDriftWithBothSides() {
        DriftRules.Comparison c = compare("{\"cpu\": 2, \"memoryGb\": 8}", "{\"cpu\": 2, \"memoryGb\": 32}");
        assertThat(c.drifted()).isTrue();
        assertThat(c.fields()).hasSize(1);
        FindingField f = c.fields().get(0);
        assertThat(f.path()).isEqualTo("/memoryGb");
        assertThat(f.expected().asInt()).isEqualTo(8);
        assertThat(f.observed().asInt()).isEqualTo(32);
        assertThat(c.severity()).isEqualTo(Severity.WARNING);
    }

    @Test
    void ignoredAndObservedOnlyFieldsNeverCount() {
        // owner is the type's ignore; powerState is reported by the provider but not desired
        assertThat(compare("{\"owner\": \"team-a\", \"cpu\": 2}", "{\"owner\": \"team-b\", \"cpu\": 2, \"powerState\": \"poweredOff\"}").drifted())
                .isFalse();
    }

    @Test
    void aSilencedFieldIsListedButDoesNotCount() {
        ObjectNode desired = json("{\"cpu\": 2}");
        IgnoredField silence = new IgnoredField("/cpu", "digest", null, "dana", Instant.parse("2026-10-23T10:00:00Z"));
        DriftRules.Comparison c = DriftRules.compare(desired, json("{\"cpu\": 8}"), Set.of(), List.of(silence), Set.of("cpu"), false);
        assertThat(c.fields()).hasSize(1);
        assertThat(c.fields().get(0).ignored()).isTrue();
        assertThat(c.drifted()).isFalse();
        assertThat(c.severity()).isEqualTo(Severity.INFO);
    }

    @Test
    void severityFollowsWhatDriftedAndWhere() {
        ObjectNode desired = json("{\"cpu\": 2, \"region\": \"eu\"}");
        assertThat(DriftRules.compare(desired, json("{\"cpu\": 4}"), Set.of(), List.of(), Set.of("cpu"), false).severity()).isEqualTo(Severity.WARNING);
        assertThat(DriftRules.compare(desired, json("{\"cpu\": 4}"), Set.of(), List.of(), Set.of("cpu"), true).severity()).isEqualTo(Severity.CRITICAL);
        assertThat(DriftRules.compare(desired, json("{\"region\": \"us\"}"), Set.of(), List.of(), Set.of("cpu"), false).severity())
                .isEqualTo(Severity.INFO);
        assertThat(DriftRules.missing(desired, List.of()).severity()).isEqualTo(Severity.CRITICAL);
        assertThat(DriftRules.missing(desired, List.of()).drifted()).isTrue();
    }

    @Test
    void onlyMutableFieldsCanBeReverted() {
        List<FindingField> fields = List.of(FindingField.of("cpu", Json.MAPPER.valueToTree(2), Json.MAPPER.valueToTree(4)),
                FindingField.of("region", Json.MAPPER.valueToTree("eu"), Json.MAPPER.valueToTree("us")));
        assertThat(DriftRules.notRevertable(fields, Set.of("cpu"))).containsExactly("/region");
        assertThat(DriftRules.notRevertable(fields.subList(0, 1), Set.of("cpu"))).isEmpty();
        JsonNode missing = DriftRules.missing(json("{\"cpu\": 2}"), List.of()).fields().get(0).observed();
        assertThat(missing.isNull()).isTrue();
    }
}
