package com.iaf.platform.providers.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.providers.domain.ReviewEvidence;
import com.iaf.platform.providers.domain.ReviewEvidence.ActionTry;
import com.iaf.platform.providers.domain.ReviewEvidence.ConnectionTest;

class InMemoryReviewEvidenceTest {

    static final Instant T0 = Instant.parse("2026-10-17T10:00:00Z");
    final InMemoryReviewEvidence store = new InMemoryReviewEvidence();

    @Test
    void evidenceIsKeptPerProviderNameAndBounded() {
        for (int i = 0; i < InMemoryReviewEvidence.MAX_CONNECTIONS + 5; i++) {
            store.recordConnection("fleet", new ConnectionTest("dev", "https://fleet-dev.example.com", null, true, i, "ok", T0.plusSeconds(i), "mateo"));
        }
        store.recordTry("fleet", new ActionTry("vm.get", "dev", true, 200, 10, null, JsonNodeFactory.instance.objectNode(), null, T0, "mateo"));
        ReviewEvidence fleet = store.evidence("fleet");
        assertThat(fleet.connections()).hasSize(InMemoryReviewEvidence.MAX_CONNECTIONS);
        assertThat(fleet.connections().get(0).durationMs()).as("the oldest go first").isEqualTo(5);
        assertThat(fleet.lastConnection("dev").orElseThrow().durationMs()).isEqualTo(InMemoryReviewEvidence.MAX_CONNECTIONS + 4);
        assertThat(fleet.tries()).hasSize(1);
        assertThat(store.evidence("other")).isEqualTo(ReviewEvidence.NONE);
        assertThat(store.evidence(null)).isEqualTo(ReviewEvidence.NONE);
    }

    @Test
    void aHugeResponseCountsAsATryWithoutItsBody() {
        ObjectNode big = JsonNodeFactory.instance.objectNode();
        big.put("blob", "x".repeat(InMemoryReviewEvidence.MAX_RESPONSE_BYTES + 1));
        store.recordTry("fleet", new ActionTry("vm.get", "dev", true, 200, 10, null, big, null, T0, "mateo"));
        ActionTry kept = store.evidence("fleet").tries().get(0);
        assertThat(kept.ok()).isTrue();
        assertThat(kept.response()).isNull();
    }
}
