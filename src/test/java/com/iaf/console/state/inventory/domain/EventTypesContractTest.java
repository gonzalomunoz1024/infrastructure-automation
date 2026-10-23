package com.iaf.console.state.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** The event types the service records are the ones its pinned contracts name: the API's enum and the topic's messages. */
class EventTypesContractTest {

    private static final Set<String> RECORDED = Arrays.stream(ResourceEventType.values()).map(ResourceEventType::wire).collect(Collectors.toSet());

    @Test
    void theApiNamesEveryEventTypeTheServiceRecords() throws IOException {
        Map<String, Object> openapi = load("contracts/openapi/console-state-service.yaml");
        List<String> named = strings(at(openapi, "components", "schemas", "ResourceEvent", "properties", "type", "enum"));
        assertThat(named).containsExactlyInAnyOrderElementsOf(RECORDED);
    }

    @Test
    void theTopicCarriesEveryEventTypeTheServiceRecords() throws IOException {
        Map<String, Object> asyncapi = load("contracts/asyncapi/console-events.yaml");
        Map<String, Object> onTopic = map(at(asyncapi, "channels", "stateResources", "messages"));
        Map<String, Object> messages = map(at(asyncapi, "components", "messages"));
        Set<String> names = onTopic.values().stream()
                .map(ref -> String.valueOf(map(ref).get("$ref")))
                .map(ref -> String.valueOf(map(messages.get(ref.substring(ref.lastIndexOf('/') + 1))).get("name")))
                .collect(Collectors.toSet());
        assertThat(names).containsExactlyInAnyOrderElementsOf(RECORDED);
    }

    private static Map<String, Object> load(String resource) throws IOException {
        try (InputStream in = EventTypesContractTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            return new Yaml().load(in);
        }
    }

    private static Object at(Map<String, Object> document, String... path) {
        Object node = document;
        for (String key : path) {
            node = map(node).get(key);
            assertThat(node).as(String.join(".", path)).isNotNull();
        }
        return node;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object node) {
        assertThat(node).isInstanceOf(Map.class);
        return (Map<String, Object>) node;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object node) {
        assertThat(node).isInstanceOf(List.class);
        return (List<String>) node;
    }
}
