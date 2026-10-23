package com.iaf.console.search.index.application;

import static com.iaf.console.search.index.domain.Fixtures.ACCOUNT;
import static com.iaf.console.search.index.domain.Fixtures.T0;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;

import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.search.TestClock;
import com.iaf.console.search.index.adapters.identity.ConfiguredAccounts;
import com.iaf.console.search.index.adapters.memory.InMemoryCheckpoints;
import com.iaf.console.search.index.adapters.memory.InMemorySearchIndex;
import com.iaf.console.search.index.domain.Fixtures;
import com.iaf.console.search.index.domain.ResourceState;
import com.iaf.console.search.index.ports.outbound.SearchIndexPort;
import com.iaf.console.search.support.FakeStateService;
import com.iaf.console.search.support.FixedTypes;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** Without Kafka the poller follows the lists from a watermark per account. */
class StatePollerTest {

    private final TestClock clock = new TestClock(T0.plus(Duration.ofHours(1)));
    private final InMemorySearchIndex index = new InMemorySearchIndex(clock);
    private final FakeStateService state = new FakeStateService();
    private final InMemoryCheckpoints checkpoints = new InMemoryCheckpoints();
    private final IndexingService indexing = new IndexingService(index, state, new FixedTypes(), checkpoints, new IndexMetrics(new SimpleMeterRegistry()),
            clock);
    private final StatePoller poller = new StatePoller(state, new ConfiguredAccounts(List.of(ACCOUNT)), index, indexing, checkpoints, clock,
            Duration.ofSeconds(1));

    @Test
    void ticksIndexWhatChangedSinceTheWatermark() {
        state.put(Fixtures.stateResource(Fixtures.VM, "web-01", 1, "active", Map.of("name", "web-01"), Map.of(), T0))
                .put(Fixtures.stateResource(Fixtures.VM2, "web-02", 1, "active", Map.of("name", "web-02"), Map.of(), T0.plusSeconds(10)));
        assertThat(poller.tick().block()).isEqualTo(2);
        assertThat(checkpoints.find(StatePoller.SOURCE + ":" + ACCOUNT).block().eventAt()).isEqualTo(T0.plusSeconds(10));
        assertThat(poller.tick().block()).as("the overlap of a second re-reads the newest change only").isEqualTo(1);
        state.put(Fixtures.stateResource(Fixtures.VM, "web-01", 2, "deleted", Map.of("name", "web-01"), Map.of(), T0.plusSeconds(20)));
        assertThat(poller.tick().block()).isEqualTo(2);
        assertThat(index.find(AccountScope.of(ACCOUNT, "global", "t"), Fixtures.VM).block().state()).isEqualTo(ResourceState.DELETED);
        assertThat(poller.status().handled()).isEqualTo(5);
        assertThat(poller.status().kind()).isEqualTo("poll");
    }

    @Test
    void aCatchUpGoesToTheIndexInBatches() {
        Map<String, Integer> calls = new ConcurrentHashMap<>();
        SearchIndexPort counted = (SearchIndexPort) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {SearchIndexPort.class},
                (proxy, method, args) -> {
                    calls.merge(method.getName(), 1, Integer::sum);
                    try {
                        return method.invoke(index, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        StatePoller batched = new StatePoller(state, new ConfiguredAccounts(List.of(ACCOUNT)), counted, indexing, checkpoints, clock,
                Duration.ofSeconds(1));
        for (int i = 0; i < 450; i++) {
            String wfrn = "wfrn:wf:compute:global:" + ACCOUNT + ":vm/vm-01jbx7q2m4k9d3c8f5r6t" + String.format("%05d", i);
            state.put(Fixtures.stateResource(wfrn, "vm-" + i, 1, "active", Map.of("name", "vm-" + i), Map.of(), T0.plusMillis(i)));
        }
        assertThat(batched.tick().block()).isEqualTo(450);
        assertThat(calls.get("indexAll")).as("a refresh per batch of 200, not per document").isEqualTo(3);
        assertThat(calls).doesNotContainKey("index");
        assertThat(index.count().block()).isEqualTo(450);
    }
}
