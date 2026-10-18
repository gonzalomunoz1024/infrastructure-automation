package com.iaf.platform.gateway.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.iaf.platform.gateway.domain.KeyedRequest;
import com.iaf.platform.gateway.domain.StoredAnswer;
import com.iaf.platform.gateway.ports.outbound.IdempotencyStore;

/**
 * What every {@link IdempotencyStore} does, run against the in-memory store and the JDBC one
 * (H2, and PostgreSQL when {@code PLATFORM_TEST_POSTGRES_URL} is set).
 */
abstract class IdempotencyStoreContract {

    static final Instant T0 = Instant.parse("2026-10-17T10:00:00Z");
    static final Duration DAY = Duration.ofHours(24);
    static final StoredAnswer OK = new StoredAnswer(202, Map.of("Content-Type", "application/json"), "{\"jobId\":\"job_1\"}");

    IdempotencyStore store;

    /** An empty store. */
    abstract IdempotencyStore newStore();

    @BeforeEach
    void setUp() {
        store = newStore();
    }

    static KeyedRequest request(String caller, String key, String fingerprint, Instant at) {
        return KeyedRequest.inFlight(caller, key, fingerprint, at, DAY);
    }

    @Test
    void theFirstClaimWinsAndLaterOnesSeeIt() {
        assertThat(store.claim(request("ana", "k1", "fp1", T0), T0)).isEmpty();
        assertThat(store.claim(request("ana", "k1", "fp2", T0.plusSeconds(1)), T0.plusSeconds(1)))
                .hasValueSatisfying(e -> {
                    assertThat(e.fingerprint()).isEqualTo("fp1");
                    assertThat(e.answered()).isFalse();
                    assertThat(e.createdAt()).isEqualTo(T0);
                    assertThat(e.expiresAt()).isEqualTo(T0.plus(DAY));
                });

        store.complete("ana", "k1", OK);
        assertThat(store.claim(request("ana", "k1", "fp1", T0.plusSeconds(2)), T0.plusSeconds(2)))
                .hasValueSatisfying(e -> assertThat(e.answer()).isEqualTo(OK));
    }

    @Test
    void anAnswerComesBackWithItsStatusHeadersAndBody() {
        StoredAnswer created = new StoredAnswer(201, Map.of("Content-Type", "application/json", "Location", "/api/v1/jobs/job_9"),
                "{\"id\":\"job_9\",\"status\":\"queued\"}");
        store.claim(request("ana", "k1", "fp1", T0), T0);
        store.complete("ana", "k1", created);

        assertThat(store.claim(request("ana", "k1", "fp1", T0), T0)).hasValueSatisfying(e -> assertThat(e.answer()).isEqualTo(created));
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
        store.release("ana", "never-claimed");
    }

    @Test
    void anAnswerHoldsForTheLifetimeOfTheKeyOnly() {
        store.claim(request("ana", "k1", "fp1", T0), T0);
        store.complete("ana", "k1", OK);
        Instant later = T0.plus(DAY).plusSeconds(1);
        assertThat(store.claim(request("ana", "k1", "fp2", later), later)).isEmpty();
        assertThat(store.claim(request("ana", "k1", "fp3", later), later))
                .hasValueSatisfying(e -> {
                    assertThat(e.fingerprint()).as("the key starts again, in flight").isEqualTo("fp2");
                    assertThat(e.answered()).isFalse();
                });
    }

    @Test
    void anAbandonedInFlightKeyCanBeClaimedAgain() {
        store.claim(request("ana", "k1", "fp1", T0), T0);
        Instant later = T0.plus(KeyedRequest.IN_FLIGHT_TIMEOUT).plusSeconds(1);
        assertThat(store.claim(request("ana", "k1", "fp1", later), later)).isEmpty();
    }
}
