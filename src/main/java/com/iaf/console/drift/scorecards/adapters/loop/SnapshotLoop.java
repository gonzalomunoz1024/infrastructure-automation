package com.iaf.console.drift.scorecards.adapters.loop;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import com.iaf.console.drift.scorecards.application.ScorecardSnapshots;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The daily recount: on its interval, every account without today's snapshot is recounted from
 * its evaluations and snapshotted. Any replica may run it; a second run on the same day replaces
 * the snapshot with the same numbers. A tick never overlaps the one before it.
 */
public final class SnapshotLoop implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SnapshotLoop.class);
    static final Duration TICK_LIMIT = Duration.ofMinutes(30);

    private final ScorecardSnapshots snapshots;
    private final Duration interval;
    private Disposable running;

    public SnapshotLoop(ScorecardSnapshots snapshots, Duration interval) {
        this.snapshots = snapshots;
        this.interval = interval;
    }

    @Override
    public synchronized void start() {
        if (running != null) {
            return;
        }
        running = Flux.interval(interval, interval)
                .onBackpressureDrop()
                .concatMap(n -> Mono.defer(snapshots::tick).timeout(TICK_LIMIT).onErrorResume(e -> {
                    log.warn("scorecard snapshot tick failed: {}", e.toString());
                    return Mono.empty();
                }), 1)
                .subscribe();
        log.info("scorecard snapshots: every {}", interval);
    }

    @Override
    public synchronized void stop() {
        if (running != null) {
            running.dispose();
            running = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return running != null;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }
}
