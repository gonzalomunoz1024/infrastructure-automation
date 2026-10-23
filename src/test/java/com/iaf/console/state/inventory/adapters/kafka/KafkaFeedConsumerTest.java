package com.iaf.console.state.inventory.adapters.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.state.inventory.domain.Json;

class KafkaFeedConsumerTest {

    @Test
    void identitysClosedActionShredsTheAccount() throws Exception {
        assertThat(KafkaFeedConsumer.closes(data("""
                {"version": 4, "action": "closed", "snapshot": {"status": "closed", "closedAt": "2026-10-23T15:00:00Z", "closeReason": "team disbanded"}}
                """))).isTrue();
        assertThat(KafkaFeedConsumer.closes(data("{\"version\": 2, \"action\": \"deleted\"}"))).isTrue();
        assertThat(KafkaFeedConsumer.closes(data("{\"version\": 3, \"action\": \"updated\", \"snapshot\": {\"status\": \"closed\"}}"))).isTrue();
    }

    @Test
    void aSuspensionOrAnUpdateKeepsTheKey() throws Exception {
        assertThat(KafkaFeedConsumer.closes(data("{\"version\": 2, \"action\": \"suspended\", \"snapshot\": {\"status\": \"suspended\"}}"))).isFalse();
        assertThat(KafkaFeedConsumer.closes(data("{\"version\": 2, \"action\": \"updated\", \"snapshot\": {\"status\": \"active\"}}"))).isFalse();
    }

    private static JsonNode data(String json) throws Exception {
        return Json.MAPPER.readTree(json);
    }
}
