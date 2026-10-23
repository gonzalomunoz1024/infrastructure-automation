package com.iaf.console.drift.drift.adapters.loop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.iaf.console.drift.drift.application.InventorySync;
import com.iaf.console.drift.drift.application.RevertFollower;
import com.iaf.console.drift.drift.application.ShardScanner;

import reactor.core.publisher.Mono;

/** The loops other parts of the service hand in run beside the drift loops, start after them and stop with them. */
class InProcessLoopsMoreTest {

    @Test
    void aHandedInLoopTicksOnItsPeriodAndStopsWithTheRest() throws InterruptedException {
        ShardScanner scanner = mock(ShardScanner.class);
        InventorySync sync = mock(InventorySync.class);
        RevertFollower follower = mock(RevertFollower.class);
        when(sync.tick()).thenReturn(Mono.empty());
        when(follower.tick()).thenReturn(Mono.empty());
        when(scanner.scanAll(any())).thenReturn(Mono.empty());
        AtomicInteger ticks = new AtomicInteger();
        InProcessLoops loops = new InProcessLoops(scanner, sync, follower, null, Duration.ofHours(1), Duration.ofHours(1), List.of(
                new InProcessLoops.Loop("alarms", Duration.ZERO, Duration.ofMillis(50), () -> Mono.fromRunnable(ticks::incrementAndGet)),
                new InProcessLoops.Loop("utilisation", Duration.ofHours(1), Duration.ofHours(1), Mono::empty)));
        assertThat(loops.names()).containsExactly("sync", "follower", "alarms", "utilisation");
        assertThat(new InProcessLoops(scanner, sync, follower, Duration.ofSeconds(1), Duration.ofHours(1), Duration.ofHours(1)).names())
                .containsExactly("sync", "scan", "follower");

        loops.start();
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (ticks.get() < 3 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(ticks.get()).isGreaterThanOrEqualTo(3);
            assertThat(loops.isRunning()).isTrue();
        } finally {
            loops.stop();
        }
        assertThat(loops.isRunning()).isFalse();
        int after = ticks.get();
        Thread.sleep(200);
        assertThat(ticks.get()).as("no tick after the stop").isLessThanOrEqualTo(after + 1);
    }
}
