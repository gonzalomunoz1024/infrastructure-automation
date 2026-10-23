package com.iaf.console.drift.drift.adapters.loop;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import reactor.core.Disposable;
import reactor.core.publisher.Mono;

class InProcessLoopsTest {

    @Test
    void aTickThatNeverEndsIsCutAtTheLimitAndTheNextTickRuns() throws InterruptedException {
        AtomicInteger ticks = new AtomicInteger();
        Disposable loop = InProcessLoops.loop("test", Duration.ZERO, Duration.ofMillis(50), Duration.ofMillis(200),
                () -> ticks.incrementAndGet() == 1 ? Mono.<Void>never() : Mono.<Void>empty());
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (ticks.get() < 2 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(ticks.get()).isGreaterThanOrEqualTo(2);
        } finally {
            loop.dispose();
        }
    }
}
