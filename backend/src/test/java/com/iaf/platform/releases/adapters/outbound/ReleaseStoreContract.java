package com.iaf.platform.releases.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.iaf.platform.releases.domain.Release;
import com.iaf.platform.releases.domain.Release.Gate;
import com.iaf.platform.releases.domain.Release.HistoryEntry;
import com.iaf.platform.releases.domain.Release.Jobs;
import com.iaf.platform.releases.domain.ReleaseStatus;
import com.iaf.platform.releases.ports.outbound.ReleaseStore;

/** What every {@link ReleaseStore} does, in memory and in a database. */
abstract class ReleaseStoreContract {

    static final Instant NOW = Instant.parse("2026-10-18T09:15:30.123456789Z");

    ReleaseStore store;

    abstract ReleaseStore newStore();

    /** A new store over the same storage, as after a restart; null when the storage does not outlive the store. */
    abstract ReleaseStore reopen();

    @BeforeEach
    void setUp() {
        store = newStore();
    }

    static final List<Integer> STAGES = List.of(10, 50, 100);

    static Release canary(String id, String workflow, Instant startedAt) {
        return Release.canary(id, workflow, "1.0.0", "1.1.0", STAGES, startedAt, "ana");
    }

    /** Halfway through: gates judged and one not yet (its {@code passed} is null), counts and history. */
    static Release canaryInProgress(Instant now) {
        Instant started = now.minus(Duration.ofHours(3));
        Instant stage = now.minus(Duration.ofMinutes(50));
        List<Gate> gates = List.of(
                new Gate("success-rate", "Success rate", true, 100.0, 92.3, "100.0% on the candidate vs 93.3% on the current version"),
                new Gate("p95", "p95 duration", true, 38400.0, 43320.0, "38.4s on the candidate vs 36.1s on the current version"),
                new Gate("error-types", "Error types", true, 0.0, 0.0, "no failures on the candidate"),
                new Gate("sample", "Sample size", null, 14.0, 20.0, "14 of 20 jobs; 50m of the 2h minimum"));
        return new Release("rel_canary1", "vm-restart", "1.1.0", "1.2.0", Release.STRATEGY_CANARY, ReleaseStatus.CANARY, 1, STAGES, 50,
                started, stage, null, gates, new Jobs(15, 14), List.of(
                        new HistoryEntry(started, "canary started at 10%", "mateo"),
                        new HistoryEntry(stage, "advanced to 50%", "platform")));
    }

    /** Every gate passed at every stage. */
    static Release promotedYesterday(Instant now) {
        Instant ended = now.minus(Duration.ofDays(1));
        Instant started = ended.minus(Duration.ofHours(7));
        Instant stage = ended.minus(Duration.ofMinutes(138));
        List<Gate> gates = List.of(new Gate("sample", "Sample size", true, 41.0, 20.0, "41 jobs over 2h 18m in the stage"));
        return new Release("rel_promoted1", "create-vm", "1.9.0", "2.0.1", Release.STRATEGY_CANARY, ReleaseStatus.PROMOTED, 2, STAGES, 100,
                started, stage, ended, gates, new Jobs(58, 41), List.of(
                        new HistoryEntry(started, "canary started at 10%", "gonzalo"),
                        new HistoryEntry(stage, "advanced to 100%", "platform"),
                        new HistoryEntry(ended, "promoted: every gate passed at 100%", "platform")));
    }

    /** Rolled back at its first stage by a failed gate. */
    static Release rolledBackLastWeek(Instant now) {
        Instant started = now.minus(Duration.ofDays(8));
        Instant ended = started.plus(Duration.ofMinutes(102));
        List<Gate> gates = List.of(new Gate("error-types", "Error types", false, 1.0, 0.0, "new on the candidate: ReplicaSetNotReady"));
        return new Release("rel_rolledback1", "mongodb-upgrade", "3.0.2", "3.1.0", Release.STRATEGY_CANARY, ReleaseStatus.ROLLED_BACK, 0, STAGES, 0,
                started, started, ended, gates, new Jobs(44, 36), List.of(
                        new HistoryEntry(started, "canary started at 10%", "ana"),
                        new HistoryEntry(ended, "rolled back: error-types gate failed", "platform")));
    }

    @Test
    void readsAReleaseBackExactly() {
        Release canary = canaryInProgress(NOW);
        Release rolledBack = rolledBackLastWeek(NOW);
        assertThat(store.save(canary)).isEqualTo(canary);
        store.save(rolledBack);
        assertThat(store.find(canary.id())).contains(canary);
        assertThat(store.find(rolledBack.id())).contains(rolledBack);
        assertThat(store.find("rel_unknown")).isEmpty();
        assertThat(store.find(null)).isEmpty();
    }

    @Test
    void listsNewestFirstThenByIdAndFiltersByWorkflow() {
        store.save(canary("rel_b", "vm-restart", NOW));
        store.save(canary("rel_a", "vm-restart", NOW));
        store.save(canary("rel_old", "vm-restart", NOW.minusSeconds(60)));
        store.save(canary("rel_close", "create-vm", NOW.minusNanos(1)));
        assertThat(store.list(null)).extracting(Release::id).containsExactly("rel_a", "rel_b", "rel_close", "rel_old");
        assertThat(store.list(" ")).hasSize(4);
        assertThat(store.list("vm-restart")).extracting(Release::id).containsExactly("rel_a", "rel_b", "rel_old");
        assertThat(store.list("unknown")).isEmpty();
    }

    @Test
    void activeReleasesAreTheCanariesAndThePausedOnesNewestFirst() {
        Release paused = canary("rel_paused", "vm-restart", NOW.minusSeconds(30)).paused(NOW, "ana");
        store.save(paused);
        store.save(canary("rel_canary", "create-vm", NOW));
        store.save(promotedYesterday(NOW));
        store.save(rolledBackLastWeek(NOW));
        assertThat(store.active()).extracting(Release::id).containsExactly("rel_canary", "rel_paused");
        assertThat(store.active("vm-restart")).contains(paused);
        assertThat(store.active("create-vm")).map(Release::id).contains("rel_canary");
        assertThat(store.active("mongodb-upgrade")).isEmpty();
        assertThat(store.active((String) null)).isEmpty();
    }

    @Test
    void theNewestActiveReleaseDecidesForItsWorkflow() {
        store.save(canary("rel_older", "vm-restart", NOW.minusSeconds(60)));
        store.save(canary("rel_newer", "vm-restart", NOW));
        assertThat(store.active("vm-restart")).map(Release::id).contains("rel_newer");
    }

    @Test
    void saveReplacesTheReleaseWithTheSameId() {
        Release canary = store.save(canary("rel_1", "vm-restart", NOW));
        Release promoted = canary.promoted(NOW.plusSeconds(60), "promoted: every gate passed", "platform");
        store.save(promoted);
        assertThat(store.list(null)).containsExactly(promoted);
        assertThat(store.active()).isEmpty();
    }

    @Test
    void keepsWhatItWroteAcrossARestart() {
        Release canary = store.save(canaryInProgress(NOW));
        ReleaseStore after = reopen();
        assumeTrue(after != null, "this store does not outlive the process");
        assertThat(after.active("vm-restart")).contains(canary);
    }
}
