package com.iaf.console.drift.drift.application;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.drift.drift.domain.Finding;
import com.iaf.console.drift.drift.domain.RevertStatus;
import com.iaf.console.drift.drift.ports.outbound.FindingStorePort;
import com.iaf.console.drift.drift.ports.outbound.WatchStorePort;
import com.iaf.console.platform.client.PlatformException;
import com.iaf.console.platform.client.PlatformPort;
import com.iaf.console.platform.model.JobStatus;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Follows the revert jobs: when one ends, the finding learns how, the resource is read from the
 * state service again (its attributes may have been re-registered) and is due at once, so the
 * next read closes the finding as reverted when the provider agrees. A failed job leaves the
 * finding open with the failure on it, for another revert or another decision.
 */
public final class RevertFollower {

    private static final Logger log = LoggerFactory.getLogger(RevertFollower.class);
    private static final int BATCH = 200;

    private final FindingStorePort findings;
    private final WatchStorePort watches;
    private final PlatformPort platform;
    private final InventorySync sync;
    private final ShardScanner scanner;
    private final FindingEvents events;
    private final DriftMetrics metrics;
    private final Clock clock;

    public RevertFollower(FindingStorePort findings, WatchStorePort watches, PlatformPort platform, InventorySync sync, ShardScanner scanner,
            FindingEvents events, DriftMetrics metrics, Clock clock) {
        this.findings = findings;
        this.watches = watches;
        this.platform = platform;
        this.sync = sync;
        this.scanner = scanner;
        this.events = events;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** One pass over the running reverts; answers how many ended. */
    public Mono<Integer> tick() {
        return findings.findReverting(BATCH)
                .concatMap(f -> follow(f).onErrorResume(e -> {
                    metrics.error("follower");
                    log.warn("finding {}: the revert job {} could not be followed: {}", f.findingId(), f.revertJobId(), e.toString());
                    return Mono.just(0);
                }))
                .reduce(0, Integer::sum);
    }

    private Mono<Integer> follow(Finding f) {
        return platform.getJob(f.revertJobId())
                .onErrorResume(PlatformException.class, e -> e.isNotFound() ? Mono.empty() : Mono.error(e))
                .flatMap(job -> {
                    if (!job.finished()) {
                        return Mono.just(0);
                    }
                    RevertStatus how = job.status() == JobStatus.SUCCEEDED ? RevertStatus.SUCCEEDED : RevertStatus.FAILED;
                    log.info("finding {}: revert job {} ended {}", f.findingId(), job.id(), job.status().id());
                    Finding ended = f.revertEnded(how);
                    return findings.save(ended)
                            .flatMap(saved -> ended.storedAs(saved) ? events.updated(saved).thenReturn(saved) : Mono.just(saved))
                            .flatMap(saved -> sync.refresh(f.wfrn()).onErrorResume(e -> Mono.empty())
                                    .then(WatchUpdates.update(watches, f.wfrn(), w -> w.toBuilder().nextCheckAt(clock.instant()).build()))
                                    .then(how == RevertStatus.SUCCEEDED ? scanner.checkNow(f.wfrn()).then() : Mono.empty())
                                    .onErrorResume(e -> {
                                        log.warn("finding {}: the read after the revert failed: {}", f.findingId(), e.toString());
                                        return Mono.empty();
                                    })
                                    .thenReturn(1));
                })
                .defaultIfEmpty(0);
    }
}
