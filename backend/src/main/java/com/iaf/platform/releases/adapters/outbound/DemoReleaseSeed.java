package com.iaf.platform.releases.adapters.outbound;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Component;

import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.releases.domain.Release;
import com.iaf.platform.releases.domain.Release.Gate;
import com.iaf.platform.releases.domain.Release.HistoryEntry;
import com.iaf.platform.releases.domain.Release.Jobs;
import com.iaf.platform.releases.domain.ReleaseStatus;
import com.iaf.platform.releases.ports.outbound.ReleaseStore;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Demo history ({@code platform.demo.seed-history}): a canary half way through, a release
 * promoted yesterday and one rolled back last week by the error-types gate. Fixed numbers; it
 * does not depend on jobs existing. The names are sample content, not platform knowledge.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DemoReleaseSeed {

    static final String CANARY_ID = "rel_demo_vm_restart";
    static final String PROMOTED_ID = "rel_demo_create_vm";
    static final String ROLLED_BACK_ID = "rel_demo_mongo_rollback";
    static final String REPUBLISHED_ID = "rel_demo_mongo_republish";

    private static final String PLATFORM = "platform";
    private static final List<Integer> STAGES = List.of(10, 50, 100);

    private final ReleaseStore store;
    private final PlatformProperties props;
    private final Clock clock;

    @PostConstruct
    public void seed() {
        if (!props.getDemo().isSeedHistory()) {
            return;
        }
        Instant now = clock.instant();
        store.save(canaryInProgress(now));
        store.save(promotedYesterday(now));
        store.save(rolledBackLastWeek(now));
        store.save(republished(now));
        log.info("seeded {} demo releases", 4);
    }

    /** vm-restart 1.1.0 -> 1.2.0: stage 1 of 3 (50%), started three hours ago, three gates judged, the sample still building up. */
    static Release canaryInProgress(Instant now) {
        Instant started = now.minus(Duration.ofHours(3));
        Instant stage = now.minus(Duration.ofMinutes(50));
        List<Gate> gates = List.of(
                new Gate("success-rate", "Success rate", true, 100.0, 92.3, "100.0% on the candidate vs 93.3% on the current version (may trail by 1 point)"),
                new Gate("p95", "p95 duration", true, 38400.0, 43320.0, "38.4s on the candidate vs 36.1s on the current version (may be 20% slower)"),
                new Gate("error-types", "Error types", true, 0.0, 0.0, "no failures on the candidate"),
                new Gate("sample", "Sample size", null, 14.0, 20.0, "14 of 20 jobs, 0.8 of 2h"));
        return new Release(CANARY_ID, "vm-restart", "1.1.0", "1.2.0", Release.STRATEGY_CANARY, ReleaseStatus.CANARY, 1, STAGES, 50,
                started, stage, null, gates, new Jobs(15, 14), List.of(
                        new HistoryEntry(started, "canary started at 10%", "mateo"),
                        new HistoryEntry(stage, "advanced to 50%", PLATFORM)));
    }

    /** create-vm 1.9.0 -> 2.0.1: every gate passed at every stage; promoted yesterday. */
    static Release promotedYesterday(Instant now) {
        Instant ended = now.minus(Duration.ofDays(1));
        Instant started = ended.minus(Duration.ofHours(7));
        Instant stage = ended.minus(Duration.ofHours(2)).minus(Duration.ofMinutes(18));
        List<Gate> gates = List.of(
                new Gate("success-rate", "Success rate", true, 98.1, 97.0, "98.1% on the candidate vs 98.0% on the current version (may trail by 1 point)"),
                new Gate("p95", "p95 duration", true, 71300.0, 86400.0, "71.3s on the candidate vs 72.0s on the current version (may be 20% slower)"),
                new Gate("error-types", "Error types", true, 0.0, 0.0, "every error class on the candidate (QuotaExceeded) also occurs on the current version"),
                new Gate("sample", "Sample size", true, 41.0, 20.0, "41 jobs over 2.3h in the stage"));
        return new Release(PROMOTED_ID, "create-vm", "1.9.0", "2.0.1", Release.STRATEGY_CANARY, ReleaseStatus.PROMOTED, 2, STAGES, 100,
                started, stage, ended, gates, new Jobs(58, 41), List.of(
                        new HistoryEntry(started, "canary started at 10%", "gonzalo"),
                        new HistoryEntry(started.plus(Duration.ofHours(2)).plus(Duration.ofMinutes(25)), "advanced to 50%", PLATFORM),
                        new HistoryEntry(stage, "advanced to 100%", PLATFORM),
                        new HistoryEntry(ended, "promoted: every gate passed at 100%", PLATFORM)));
    }

    /** mongodb-upgrade 3.0.2 -> 3.1.0: an error class the current version never had; rolled back at 10% last week. */
    static Release rolledBackLastWeek(Instant now) {
        Instant started = now.minus(Duration.ofDays(8));
        Instant ended = started.plus(Duration.ofHours(1)).plus(Duration.ofMinutes(42));
        String reason = "error-types gate failed (new on the candidate: ReplicaSetNotReady)";
        List<Gate> gates = List.of(
                new Gate("success-rate", "Success rate", true, 97.2, 96.8, "97.2% on the candidate vs 97.8% on the current version (may trail by 1 point)"),
                new Gate("p95", "p95 duration", true, 412000.0, 506400.0, "412.0s on the candidate vs 422.0s on the current version (may be 20% slower)"),
                new Gate("error-types", "Error types", false, 1.0, 0.0, "new on the candidate: ReplicaSetNotReady"),
                new Gate("sample", "Sample size", null, 36.0, 20.0, "36 of 20 jobs, 1.7 of 2h"));
        return new Release(ROLLED_BACK_ID, "mongodb-upgrade", "3.0.2", "3.1.0", Release.STRATEGY_CANARY, ReleaseStatus.ROLLED_BACK, 0, STAGES, 0,
                started, started, ended, gates, new Jobs(44, 36), List.of(
                        new HistoryEntry(started, "canary started at 10%", "ana"),
                        new HistoryEntry(ended, "rolled back: " + reason, PLATFORM)));
    }

    /** The same version published again to everyone once the fix was in. */
    static Release republished(Instant now) {
        Instant at = now.minus(Duration.ofDays(6));
        return new Release(REPUBLISHED_ID, "mongodb-upgrade", "3.0.2", "3.1.0", Release.STRATEGY_ALL, ReleaseStatus.PROMOTED, 0, List.of(100), 100,
                at, at, at, List.of(), Jobs.NONE, List.of(new HistoryEntry(at, "published to everyone", "ana")));
    }
}
