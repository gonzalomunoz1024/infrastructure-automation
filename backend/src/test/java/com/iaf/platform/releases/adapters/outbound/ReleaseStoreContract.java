package com.iaf.platform.releases.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.iaf.platform.releases.domain.Release;
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

    static Release canary(String id, String workflow, Instant startedAt) {
        return Release.canary(id, workflow, "1.0.0", "1.1.0", List.of(10, 50, 100), startedAt, "ana");
    }

    @Test
    void readsAReleaseBackExactly() {
        Release canary = DemoReleaseSeed.canaryInProgress(NOW);
        Release rolledBack = DemoReleaseSeed.rolledBackLastWeek(NOW);
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
        store.save(DemoReleaseSeed.promotedYesterday(NOW));
        store.save(DemoReleaseSeed.rolledBackLastWeek(NOW));
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
        Release canary = store.save(DemoReleaseSeed.canaryInProgress(NOW));
        ReleaseStore after = reopen();
        assumeTrue(after != null, "this store does not outlive the process");
        assertThat(after.active("vm-restart")).contains(canary);
    }
}
