package com.iaf.platform.providers.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.providers.domain.ReviewEvidence;
import com.iaf.platform.providers.domain.ReviewEvidence.ActionTry;
import com.iaf.platform.providers.domain.ReviewEvidence.ConnectionTest;
import com.iaf.platform.providers.ports.outbound.ReviewEvidencePort;

/** What every {@link ReviewEvidencePort} does, in memory and in a database: the same bounds, the same order. */
abstract class ReviewEvidenceContract {

    static final Instant T0 = Instant.parse("2026-10-17T10:00:00.123456789Z");

    ReviewEvidencePort store;

    abstract ReviewEvidencePort newStore();

    /** A new store over the same storage, as after a restart; null when the storage does not outlive the store. */
    abstract ReviewEvidencePort reopen();

    @BeforeEach
    void setUp() {
        store = newStore();
    }

    static ConnectionTest connection(String environment, boolean ok, long ms, Instant at) {
        return new ConnectionTest(environment, "https://fleet-" + environment + ".example.com/", "vault:fleet/token", ok, ms, ok ? "HTTP 200" : "HTTP 503", at, "mateo");
    }

    static ActionTry answered(String action, long ms, Instant at) {
        ObjectNode response = JsonNodeFactory.instance.objectNode().put("id", "vm-1").put("powerState", "poweredOn");
        response.putArray("tags").add("web").add(3);
        return new ActionTry(action, "dev", true, 200, ms, null, response, null, at, "mateo");
    }

    static ActionTry failed(String action, Instant at) {
        return new ActionTry(action, "dev", false, 404, 30, "not_found", null, Boolean.FALSE, at, "ana");
    }

    @Test
    void nothingRecordedIsNoEvidenceAndNullsRecordNothing() {
        assertThat(store.evidence("fleet")).isEqualTo(ReviewEvidence.NONE);
        assertThat(store.evidence(null)).isEqualTo(ReviewEvidence.NONE);
        store.recordConnection(null, connection("dev", true, 10, T0));
        store.recordConnection("fleet", null);
        store.recordTry(null, answered("vm.get", 10, T0));
        store.recordTry("fleet", null);
        assertThat(store.evidence("fleet")).isEqualTo(ReviewEvidence.NONE);
    }

    @Test
    void readsWhatWasRecordedBackExactlyPerProviderOldestFirst() {
        ConnectionTest dev = connection("dev", true, 120, T0);
        ConnectionTest test = connection("test", false, 5000, T0.plusSeconds(1));
        ActionTry ok = answered("vm.get", 80, T0.plusSeconds(2));
        ActionTry notFound = failed("vm.delete", T0.plusSeconds(3));
        store.recordConnection("fleet", dev);
        store.recordConnection("fleet", test);
        store.recordTry("fleet", ok);
        store.recordTry("fleet", notFound);
        store.recordConnection("vault", connection("dev", true, 9, T0));

        ReviewEvidence fleet = store.evidence("fleet");
        assertThat(fleet.connections()).containsExactly(dev, test);
        assertThat(fleet.tries()).containsExactly(ok, notFound);
        assertThat(fleet.lastConnection("dev")).contains(dev);
        assertThat(fleet.lastAnswer("vm.get", "dev")).contains(ok);
        assertThat(fleet.tries().get(1).verified()).isFalse();
        assertThat(store.evidence("vault").connections()).hasSize(1);
        assertThat(store.evidence("vault").tries()).isEmpty();
        assertThat(store.evidence("other")).isEqualTo(ReviewEvidence.NONE);
    }

    @Test
    void connectionTestsAreKeptPerProviderNameAndBounded() {
        for (int i = 0; i < InMemoryReviewEvidence.MAX_CONNECTIONS + 5; i++) {
            store.recordConnection("fleet", new ConnectionTest("dev", "https://fleet-dev.example.com", null, true, i, "ok", T0.plusSeconds(i), "mateo"));
        }
        store.recordTry("fleet", answered("vm.get", 10, T0));
        store.recordConnection("vault", connection("dev", true, 9, T0));

        ReviewEvidence fleet = store.evidence("fleet");
        assertThat(fleet.connections()).hasSize(InMemoryReviewEvidence.MAX_CONNECTIONS);
        assertThat(fleet.connections().get(0).durationMs()).as("the oldest go first").isEqualTo(5);
        assertThat(fleet.lastConnection("dev").orElseThrow().durationMs()).isEqualTo(InMemoryReviewEvidence.MAX_CONNECTIONS + 4);
        assertThat(fleet.tries()).hasSize(1);
        assertThat(store.evidence("vault").connections()).as("another provider's evidence is its own").hasSize(1);
    }

    @Test
    void triesAreKeptPerProviderNameAndBounded() {
        for (int i = 0; i < InMemoryReviewEvidence.MAX_TRIES + 3; i++) {
            store.recordTry("fleet", answered("vm.get", i, T0.plusSeconds(i)));
        }
        ReviewEvidence fleet = store.evidence("fleet");
        assertThat(fleet.tries()).hasSize(InMemoryReviewEvidence.MAX_TRIES);
        assertThat(fleet.tries().get(0).durationMs()).as("the oldest go first").isEqualTo(3);
        assertThat(fleet.lastAnswer("vm.get", "dev").orElseThrow().durationMs()).isEqualTo(InMemoryReviewEvidence.MAX_TRIES + 2);
        assertThat(fleet.connections()).isEmpty();
    }

    @Test
    void aHugeResponseCountsAsATryWithoutItsBody() {
        ObjectNode big = JsonNodeFactory.instance.objectNode();
        big.put("blob", "x".repeat(InMemoryReviewEvidence.MAX_RESPONSE_BYTES + 1));
        store.recordTry("fleet", new ActionTry("vm.get", "dev", true, 200, 10, null, big, null, T0, "mateo"));
        ActionTry kept = store.evidence("fleet").tries().get(0);
        assertThat(kept.ok()).isTrue();
        assertThat(kept.response()).isNull();
        assertThat(kept).isEqualTo(new ActionTry("vm.get", "dev", true, 200, 10, null, null, null, T0, "mateo"));
    }

    @Test
    void keepsEvidenceAcrossARestart() {
        store.recordConnection("fleet", connection("dev", true, 120, T0));
        store.recordTry("fleet", answered("vm.get", 80, T0.plusSeconds(2)));
        store.recordTry("fleet", failed("vm.delete", T0.plusSeconds(3)));
        ReviewEvidence before = store.evidence("fleet");

        ReviewEvidencePort after = reopen();
        assumeTrue(after != null, "this store does not outlive the process");
        assertThat(after.evidence("fleet")).isEqualTo(before);
        assertThat(after.evidence("fleet").tries()).hasSize(2);
    }
}
