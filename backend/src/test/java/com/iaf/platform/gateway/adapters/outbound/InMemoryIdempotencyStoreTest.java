package com.iaf.platform.gateway.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.iaf.platform.gateway.domain.KeyedRequest;
import com.iaf.platform.gateway.domain.StoredAnswer;

class InMemoryIdempotencyStoreTest {

    static final Instant T0 = Instant.parse("2026-10-17T10:00:00Z");
    static final Duration DAY = Duration.ofHours(24);
    static final StoredAnswer OK = new StoredAnswer(202, Map.of("Content-Type", "application/json"), "{\"jobId\":\"job_1\"}");

    InMemoryIdempotencyStore store = new InMemoryIdempotencyStore(100);

    static KeyedRequest request(String caller, String key, String fingerprint, Instant at) {
        return KeyedRequest.inFlight(caller, key, fingerprint, at, DAY);
    }

    @Test
    void theFirstClaimWinsAndLaterOnesSeeIt() {
        assertThat(store.claim(request("ana", "k1", "fp1", T0), T0)).isEmpty();
        assertThat(store.claim(request("ana", "k1", "fp2", T0.plusSeconds(1)), T0.plusSeconds(1)))
                .hasValueSatisfying(e -> assertThat(e.fingerprint()).isEqualTo("fp1"));

        store.complete("ana", "k1", OK);
        assertThat(store.claim(request("ana", "k1", "fp1", T0.plusSeconds(2)), T0.plusSeconds(2)))
                .hasValueSatisfying(e -> assertThat(e.answer()).isEqualTo(OK));
    }

    @Test
    void keysBelongToTheirCaller() {
        assertThat(store.claim(request("ana", "k1", "fp1", T0), T0)).isEmpty();
        assertThat(store.claim(request("bob", "k1", "fp1", T0), T0)).isEmpty();
    }

    @Test
    void releasingFreesAnUnansweredKeyButNeverAnAnsweredOne() {
        store.claim(request("ana", "k1", "fp1", T0), T0);
        store.release("ana", "k1");
        assertThat(store.claim(request("ana", "k1", "fp2", T0), T0)).isEmpty();

        store.complete("ana", "k1", OK);
        store.release("ana", "k1");
        assertThat(store.claim(request("ana", "k1", "fp3", T0), T0)).isPresent();
    }

    @Test
    void anAnswerHoldsForTheLifetimeOfTheKeyOnly() {
        store.claim(request("ana", "k1", "fp1", T0), T0);
        store.complete("ana", "k1", OK);
        Instant later = T0.plus(DAY).plusSeconds(1);
        assertThat(store.claim(request("ana", "k1", "fp2", later), later)).isEmpty();
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void anAbandonedInFlightKeyCanBeClaimedAgain() {
        store.claim(request("ana", "k1", "fp1", T0), T0);
        Instant later = T0.plus(KeyedRequest.IN_FLIGHT_TIMEOUT).plusSeconds(1);
        assertThat(store.claim(request("ana", "k1", "fp1", later), later)).isEmpty();
    }

    @Test
    void keepsAtMostTheConfiguredNumberOfKeysDroppingTheOldest() {
        InMemoryIdempotencyStore small = new InMemoryIdempotencyStore(2);
        small.claim(request("ana", "k1", "fp", T0), T0);
        small.claim(request("ana", "k2", "fp", T0), T0);
        small.claim(request("ana", "k3", "fp", T0), T0);
        assertThat(small.size()).isEqualTo(2);
        assertThat(small.claim(request("ana", "k1", "fp", T0), T0)).isEmpty();
        assertThat(small.claim(request("ana", "k3", "fp", T0), T0)).isPresent();
    }
}
