package com.iaf.platform.gateway.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.iaf.platform.common.domain.ConflictException;
import com.iaf.platform.gateway.application.IdempotencyHandler.Proceed;
import com.iaf.platform.gateway.application.IdempotencyHandler.Replay;
import com.iaf.platform.gateway.domain.StoredAnswer;
import com.iaf.platform.gateway.ports.outbound.IdempotencyStore;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * What the handler does over any {@link IdempotencyStore}: run against the in-memory store and
 * the MongoDB one (when {@code PLATFORM_TEST_MONGO_URI} names a server).
 */
abstract class IdempotencyHandlerContract {

    static final Instant T0 = Instant.parse("2026-10-17T10:00:00Z");
    static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);

    IdempotencyHandler handler;
    AtomicInteger runs = new AtomicInteger();

    /** An empty store. */
    abstract IdempotencyStore newStore();

    @BeforeEach
    void setUp() {
        handler = new IdempotencyHandler(newStore(), Duration.ofHours(24), CLOCK);
    }

    Mono<JsonNode> start() {
        return Mono.fromSupplier(() -> JsonNodeFactory.instance.objectNode().put("jobId", "job_" + runs.incrementAndGet()));
    }

    @Test
    void aNewKeyProceedsAndASucceededOneIsReplayed() {
        StepVerifier.create(handler.claim("ana", "k1", "fp")).assertNext(c -> assertThat(c).isInstanceOf(Proceed.class)).verifyComplete();
        StepVerifier.create(handler.complete("ana", "k1", new StoredAnswer(202, Map.of(), "{\"jobId\":\"job_1\"}"))).verifyComplete();
        StepVerifier.create(handler.claim("ana", "k1", "fp"))
                .assertNext(c -> assertThat(((Replay) c).answer().body()).isEqualTo("{\"jobId\":\"job_1\"}"))
                .verifyComplete();
    }

    @Test
    void theSameKeyWithAnotherRequestIsAConflict() {
        handler.claim("ana", "k1", "fp").block();
        handler.complete("ana", "k1", new StoredAnswer(202, Map.of(), "{}")).block();
        StepVerifier.create(handler.claim("ana", "k1", "other"))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(ConflictException.class).hasMessageContaining("different request"))
                .verify();
    }

    @Test
    void aSecondRequestWhileTheFirstRunsIsAConflict() {
        handler.claim("ana", "k1", "fp").block();
        StepVerifier.create(handler.claim("ana", "k1", "fp"))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(ConflictException.class).hasMessageContaining("still running"))
                .verify();
    }

    @Test
    void aFailedAnswerFreesTheKey() {
        handler.claim("ana", "k1", "fp").block();
        handler.complete("ana", "k1", new StoredAnswer(400, Map.of(), "{\"error\":\"bad\"}")).block();
        StepVerifier.create(handler.claim("ana", "k1", "other")).assertNext(c -> assertThat(c).isInstanceOf(Proceed.class)).verifyComplete();
    }

    @Test
    void anInvalidKeyIsABadRequest() {
        StepVerifier.create(handler.claim("ana", "two words", "fp")).expectError(IllegalArgumentException.class).verify();
    }

    @Test
    void onceRunsTheCallOncePerKeyAndMarksTheReplay() {
        StepVerifier.create(handler.once("ana", "k1", "fp", this::start))
                .assertNext(o -> {
                    assertThat(o.replayed()).isFalse();
                    assertThat(o.value().path("jobId").asText()).isEqualTo("job_1");
                })
                .verifyComplete();
        StepVerifier.create(handler.once("ana", "k1", "fp", this::start))
                .assertNext(o -> {
                    assertThat(o.replayed()).isTrue();
                    assertThat(o.value().path("jobId").asText()).isEqualTo("job_1");
                })
                .verifyComplete();
        StepVerifier.create(handler.once("bob", "k1", "fp", this::start))
                .assertNext(o -> assertThat(o.value().path("jobId").asText()).isEqualTo("job_2"))
                .verifyComplete();
        assertThat(runs.get()).isEqualTo(2);
    }

    @Test
    void aFailedCallFreesItsKeyForARetry() {
        StepVerifier.create(handler.once("ana", "k1", "fp", () -> Mono.error(new IllegalArgumentException("bad input"))))
                .expectError(IllegalArgumentException.class)
                .verify();
        StepVerifier.create(handler.once("ana", "k1", "fp", this::start))
                .assertNext(o -> assertThat(o.replayed()).isFalse())
                .verifyComplete();
    }
}
