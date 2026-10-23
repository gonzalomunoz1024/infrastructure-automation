package com.iaf.console.search.index.application;

import static com.iaf.console.search.index.domain.Fixtures.ACCOUNT;
import static com.iaf.console.search.index.domain.Fixtures.OTHER_ACCOUNT;
import static com.iaf.console.search.index.domain.Fixtures.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.search.TestClock;
import com.iaf.console.search.index.adapters.identity.ConfiguredAccounts;
import com.iaf.console.search.index.adapters.memory.InMemoryCheckpoints;
import com.iaf.console.search.index.adapters.memory.InMemoryRebuildRuns;
import com.iaf.console.search.index.adapters.memory.InMemorySearchIndex;
import com.iaf.console.search.index.domain.Fixtures;
import com.iaf.console.search.index.domain.RebuildRun;
import com.iaf.console.search.index.domain.ResourceState;
import com.iaf.console.search.support.FakeStateService;
import com.iaf.console.search.support.FixedTypes;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** A rebuild walks the lists, writes in batches, sweeps what is gone, and runs one at a time. */
class RebuildServiceTest {

    private final TestClock clock = new TestClock(T0.plus(Duration.ofHours(1)));
    private final InMemorySearchIndex index = new InMemorySearchIndex(clock);
    private final FakeStateService state = new FakeStateService();
    private final InMemoryRebuildRuns runs = new InMemoryRebuildRuns();
    private final IndexMetrics metrics = new IndexMetrics(new SimpleMeterRegistry());
    private final IndexingService indexing = new IndexingService(index, state, new FixedTypes(), new InMemoryCheckpoints(), metrics, clock);
    private final RebuildService rebuilds = new RebuildService(index, state, new ConfiguredAccounts(List.of(ACCOUNT, OTHER_ACCOUNT)), runs, indexing, metrics,
            clock, 2);

    @Test
    void everyAccountIsWalkedAndWhatIsGoneIsSwept() {
        state.put(Fixtures.stateResource(Fixtures.VM, "web-01", 2, "active", Map.of("name", "web-01"), Map.of(), T0))
                .put(Fixtures.stateResource(Fixtures.VM2, "web-02", 1, "pending", Map.of("name", "web-02"), Map.of(), T0.plusSeconds(1)))
                .put(Fixtures.stateResource(Fixtures.DB, "orders", 3, "deleted", Map.of("name", "orders"), Map.of(), T0.plusSeconds(2)))
                .put(Fixtures.stateResource(Fixtures.OTHERS_VM, "theirs", 1, "active", Map.of("name", "theirs"), Map.of(), T0));
        index.index(Fixtures.document("wfrn:wf:compute:global:123456789012:vm/vm-gone", "gone", 1, Map.of(), T0)).block();
        clock.advance(Duration.ofSeconds(1));
        RebuildRun started = rebuilds.start(null, "key-1").block();
        assertThat(started.status()).isEqualTo(RebuildRun.Status.RUNNING);
        RebuildRun done = await(started.runId());
        assertThat(done.status()).isEqualTo(RebuildRun.Status.SUCCEEDED);
        assertThat(done.indexed()).isEqualTo(4);
        assertThat(index.find(AccountScope.of(ACCOUNT, "global", "t"), "wfrn:wf:compute:global:123456789012:vm/vm-gone").blockOptional()).isEmpty();
        assertThat(index.find(AccountScope.of(ACCOUNT, "global", "t"), Fixtures.DB).block().state()).isEqualTo(ResourceState.DELETED);
        assertThat(index.find(AccountScope.of(OTHER_ACCOUNT, "global", "t"), Fixtures.OTHERS_VM).block().name()).isEqualTo("theirs");
        assertThat(rebuilds.start(null, "key-1").block().runId()).as("the same Idempotency-Key answers the same run").isEqualTo(started.runId());
    }

    @Test
    void oneRunAtATime() {
        state.slow(Duration.ofSeconds(2)).put(Fixtures.stateResource(Fixtures.VM, "web-01", 2, "active", Map.of(), Map.of(), T0));
        RebuildRun first = rebuilds.start(ACCOUNT, null).block();
        assertThatThrownBy(() -> rebuilds.start(ACCOUNT, null).block()).isInstanceOf(ConflictException.class);
        assertThat(rebuilds.rebuilding().block()).isTrue();
        assertThat(await(first.runId()).status()).isEqualTo(RebuildRun.Status.SUCCEEDED);
        assertThat(rebuilds.rebuilding().block()).isFalse();
        assertThat(rebuilds.find("rb_nope").blockOptional()).isEmpty();
    }

    private RebuildRun await(String runId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            RebuildRun run = rebuilds.find(runId).block();
            if (run != null && !run.running()) {
                return run;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("the rebuild did not finish");
    }
}
