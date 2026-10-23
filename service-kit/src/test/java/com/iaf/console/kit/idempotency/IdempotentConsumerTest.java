package com.iaf.console.kit.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Mono;

class IdempotentConsumerTest {

    private final InMemoryProcessedEvents processed = new InMemoryProcessedEvents();
    private final AtomicInteger runs = new AtomicInteger();

    @Test
    void runsAHandlerOncePerKey() {
        IdempotentConsumer consumer = new IdempotentConsumer(processed, Clock.systemUTC());
        assertThat(consumer.process("job.finished:j-1:9", () -> Mono.fromRunnable(runs::incrementAndGet)).block()).isTrue();
        assertThat(consumer.process("job.finished:j-1:9", () -> Mono.fromRunnable(runs::incrementAndGet)).block()).isFalse();
        assertThat(consumer.process("job.finished:j-1:10", () -> Mono.fromRunnable(runs::incrementAndGet)).block()).isTrue();
        assertThat(runs.get()).isEqualTo(2);
    }

    @Test
    void forgetsTheKeyWhenTheHandlerFails() {
        IdempotentConsumer consumer = new IdempotentConsumer(processed, Clock.systemUTC());
        assertThatThrownBy(() -> consumer.process("k:1:1", () -> Mono.error(new IllegalStateException("down"))).block())
                .isInstanceOf(IllegalStateException.class);
        assertThat(processed.size()).isZero();
        assertThat(consumer.process("k:1:1", () -> Mono.fromRunnable(runs::incrementAndGet)).block()).isTrue();
        assertThat(runs.get()).isEqualTo(1);
    }

    @Test
    void keysExpire() {
        Instant start = Instant.parse("2026-10-23T10:00:00Z");
        assertThat(processed.markIfNew("k", start, Duration.ofMinutes(1)).block()).isTrue();
        assertThat(processed.markIfNew("k", start.plusSeconds(30), Duration.ofMinutes(1)).block()).isFalse();
        assertThat(processed.markIfNew("k", start.plusSeconds(61), Duration.ofMinutes(1)).block()).isTrue();
        processed.forget("k").block();
        assertThat(processed.size()).isZero();
    }

    @Test
    void needsAKey() {
        IdempotentConsumer consumer = new IdempotentConsumer(processed, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        assertThatThrownBy(() -> consumer.process(" ", Mono::empty).block()).isInstanceOf(IllegalArgumentException.class);
    }
}
