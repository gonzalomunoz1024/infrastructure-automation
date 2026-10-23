package com.iaf.console.drift.drift.adapters.loop;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import com.iaf.console.drift.drift.application.InventorySync;
import com.iaf.console.drift.drift.application.RevertFollower;
import com.iaf.console.drift.drift.application.ShardScanner;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The loops inside this process, for a single replica, the demo and the tests: the inventory sync
 * (at once, then on its interval), the scan of every shard on the loop's interval (unless the
 * {@code temporal} profile runs it), and the follower of revert jobs. A tick never overlaps the
 * one before it; a failed tick, or one still running at the limit, is logged and the next one runs.
 */
public final class InProcessLoops implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(InProcessLoops.class);
    /** As the Temporal activities' limit: a tick still running then is cut, so one stuck call cannot stop its loop. */
    static final Duration TICK_LIMIT = Duration.ofMinutes(14);

    private final ShardScanner scanner;
    private final InventorySync sync;
    private final RevertFollower follower;
    private final Duration scanInterval;
    private final Duration syncInterval;
    private final Duration followerInterval;
    private final List<Disposable> running = new ArrayList<>();

    /** {@code scanInterval} null leaves the scan to someone else. */
    public InProcessLoops(ShardScanner scanner, InventorySync sync, RevertFollower follower, Duration scanInterval, Duration syncInterval,
            Duration followerInterval) {
        this.scanner = scanner;
        this.sync = sync;
        this.follower = follower;
        this.scanInterval = scanInterval;
        this.syncInterval = syncInterval;
        this.followerInterval = followerInterval;
    }

    @Override
    public synchronized void start() {
        if (!running.isEmpty()) {
            return;
        }
        running.add(loop("sync", Duration.ZERO, syncInterval, () -> sync.tick().then()));
        if (scanInterval != null) {
            running.add(loop("scan", scanInterval, scanInterval, () -> scanner.scanAll(null).then()));
        }
        running.add(loop("follower", followerInterval, followerInterval, () -> follower.tick().then()));
        log.info("drift loops: sync every {}, scan {}, follower every {}", syncInterval, scanInterval == null ? "on Temporal" : "every " + scanInterval,
                followerInterval);
    }

    private static Disposable loop(String name, Duration delay, Duration period, java.util.function.Supplier<Mono<Void>> tick) {
        return loop(name, delay, period, TICK_LIMIT, tick);
    }

    static Disposable loop(String name, Duration delay, Duration period, Duration limit, java.util.function.Supplier<Mono<Void>> tick) {
        return Flux.interval(delay, period)
                .onBackpressureDrop()
                .concatMap(n -> Mono.defer(tick).timeout(limit).onErrorResume(e -> {
                    log.warn("drift {} tick failed: {}", name, e.toString());
                    return Mono.empty();
                }), 1)
                .subscribe();
    }

    @Override
    public synchronized void stop() {
        running.forEach(Disposable::dispose);
        running.clear();
    }

    @Override
    public synchronized boolean isRunning() {
        return !running.isEmpty();
    }

    @Override
    public int getPhase() {
        // after the web server and the outbox relay
        return Integer.MAX_VALUE - 100;
    }
}
