package com.iaf.console.kit.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

class EventEnvelopeTest {

    private final ObjectMapper json = JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();

    @Test
    void serializesTheFeedShape() throws Exception {
        EventEnvelope event = EventEnvelope.of("evt-1", EventKeys.job("job.finished", "job-1", 7), "job.finished",
                        Instant.parse("2026-10-23T10:00:00Z"), "job", "job-1", "gonzalo", json.readTree("{\"status\":\"succeeded\"}"))
                .withSeq(42)
                .withContext(new EventEnvelope.Context("123456789012", "global", "wfrn:wf:compute:global:123456789012:vm/01abc", "req-1"),
                        new EventEnvelope.OnBehalfOf("u-1", "Ana", "ana@example.com"))
                .withTraceparent("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");

        JsonNode node = json.valueToTree(event);
        List<String> fields = new ArrayList<>();
        node.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("specVersion", "dataVersion", "id", "key", "seq", "type", "at", "aggregate", "aggregateId",
                "actor", "onBehalfOf", "context", "traceparent", "data");
        assertThat(node.get("specVersion").asText()).isEqualTo("1.0");
        assertThat(node.get("dataVersion").asInt()).isEqualTo(1);
        assertThat(node.get("key").asText()).isEqualTo("job.finished:job-1:7");
        assertThat(node.get("seq").asLong()).isEqualTo(42);
        assertThat(node.get("at").asText()).isEqualTo("2026-10-23T10:00:00Z");
        assertThat(node.get("context").get("accountId").asText()).isEqualTo("123456789012");
        assertThat(node.get("onBehalfOf").get("email").asText()).isEqualTo("ana@example.com");
        assertThat(node.get("data").get("status").asText()).isEqualTo("succeeded");

        EventEnvelope back = json.treeToValue(node, EventEnvelope.class);
        assertThat(back).isEqualTo(event);
    }

    @Test
    void leavesOutWhatIsAbsent() throws Exception {
        EventEnvelope event = EventEnvelope.of("evt-2", "resource.updated:r-1:1", "resource.updated", Instant.EPOCH, "resource", "r-1", "system",
                null);
        JsonNode node = json.valueToTree(event);
        assertThat(node.has("seq")).isFalse();
        assertThat(node.has("onBehalfOf")).isFalse();
        assertThat(node.has("context")).isFalse();
        assertThat(node.has("traceparent")).isFalse();
        assertThat(node.has("data")).isFalse();
        assertThat(event.accountId()).isNull();
        assertThat(event.is("resource.")).isTrue();
        assertThat(event.is("job.")).isFalse();
        assertThat(json.readValue("{\"key\":\"k:1:1\",\"type\":\"k\"}", EventEnvelope.class).specVersion()).isEqualTo("1.0");
    }
}
