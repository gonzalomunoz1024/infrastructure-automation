package com.iaf.console.search.index.application;

import static com.iaf.console.search.index.domain.Fixtures.ACCOUNT;
import static com.iaf.console.search.index.domain.Fixtures.T0;
import static com.iaf.console.search.index.domain.Fixtures.VM;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.iaf.console.kit.idempotency.IdempotentConsumer;
import com.iaf.console.kit.idempotency.InMemoryProcessedEvents;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.search.TestClock;
import com.iaf.console.search.index.adapters.memory.InMemoryCheckpoints;
import com.iaf.console.search.index.adapters.memory.InMemorySearchIndex;
import com.iaf.console.search.index.domain.EventOutcome;
import com.iaf.console.search.index.domain.Fixtures;
import com.iaf.console.search.index.domain.IndexedResource;
import com.iaf.console.search.index.domain.ResourceState;
import com.iaf.console.search.support.FakeStateService;
import com.iaf.console.search.support.FixedTypes;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** Events into the index: applied, re-read from the state service when the fold asks, de-duplicated on the key. */
class IndexingServiceTest {

    private final TestClock clock = new TestClock(T0.plus(Duration.ofMinutes(1)));
    private final InMemorySearchIndex index = new InMemorySearchIndex(clock);
    private final FakeStateService state = new FakeStateService();
    private final InMemoryCheckpoints checkpoints = new InMemoryCheckpoints();
    private final IndexMetrics metrics = new IndexMetrics(new SimpleMeterRegistry());
    private final IndexingService indexing = new IndexingService(index, state, new FixedTypes(), checkpoints, metrics, clock);
    private final EventHandler handler = new EventHandler(indexing, new IdempotentConsumer(new InMemoryProcessedEvents(), clock));
    private final AccountScope scope = AccountScope.of(ACCOUNT, "global", "test");

    @Test
    void eventsInOrderBuildTheDocument() {
        assertThat(handler.handle(Fixtures.requested(VM, "web-03", Map.of("environment", "dev"))).block()).isTrue();
        assertThat(handler.handle(Fixtures.succeeded(VM, 2, Map.of("name", "web-03", "environment", "dev"), T0.plusSeconds(2))).block()).isTrue();
        assertThat(handler.handle(Fixtures.tagged(VM, 3, Map.of("team", "web"), List.of(), T0.plusSeconds(3))).block()).isTrue();
        IndexedResource doc = index.find(scope, VM).block();
        assertThat(doc.state()).isEqualTo(ResourceState.ACTIVE);
        assertThat(doc.version()).isEqualTo(3);
        assertThat(doc.tags()).containsEntry("team", "web");
        assertThat(state.finds()).as("no re-read when the events are in order").isZero();
        assertThat(checkpoints.find(IndexingService.EVENTS).block().eventAt()).isEqualTo(T0.plusSeconds(3));
        assertThat(checkpoints.find(IndexingService.EVENTS).block().applied()).isEqualTo(3);
    }

    @Test
    void aDuplicateDeliveryIsSkipped() {
        var requested = Fixtures.requested(VM, "web-03", Map.of());
        assertThat(handler.handle(requested).block()).isTrue();
        assertThat(handler.handle(requested.withId("evt_other")).block()).as("same key, new id").isFalse();
    }

    @Test
    void aGapReadsTheResourceAgain() {
        handler.handle(Fixtures.requested(VM, "web-03", Map.of())).block();
        state.put(Fixtures.stateResource(VM, "web-03", 3, "active", Map.of("name", "web-03", "environment", "prod"), Map.of("env", "prod"), T0.plusSeconds(5)));
        EventOutcome outcome = indexing.apply(Fixtures.succeeded(VM, 3, Map.of("name", "web-03"), T0.plusSeconds(5))).block();
        assertThat(outcome).isInstanceOf(EventOutcome.Applied.class);
        IndexedResource doc = index.find(scope, VM).block();
        assertThat(doc.version()).isEqualTo(3);
        assertThat(doc.tags()).containsEntry("env", "prod");
        assertThat(doc.fields()).containsEntry("environment", "prod");
        assertThat(state.finds()).isEqualTo(1);
    }

    @Test
    void aStaleEventChangesNothing() {
        handler.handle(Fixtures.requested(VM, "web-03", Map.of())).block();
        handler.handle(Fixtures.succeeded(VM, 2, Map.of("name", "web-03"), T0.plusSeconds(2))).block();
        EventOutcome outcome = indexing.apply(Fixtures.requested(VM, "old-name", Map.of())).block();
        assertThat(outcome).isInstanceOf(EventOutcome.Ignored.class);
        assertThat(index.find(scope, VM).block().name()).isEqualTo("web-03");
    }

    @Test
    void aFindingOnAnUnknownResourceReadsItAndAResourceTheStateServiceLostIsRemoved() {
        state.put(Fixtures.stateResource(VM, "web-03", 2, "active", Map.of("name", "web-03"), Map.of(), T0));
        indexing.apply(Fixtures.finding("FindingOpened", VM, "f-1", 1, "warning", T0.plusSeconds(1))).block();
        IndexedResource doc = index.find(scope, VM).block();
        assertThat(doc).isNotNull();
        assertThat(doc.drift()).as("the re-read serves the state service's view, which has no finding yet").isFalse();
        indexing.apply(Fixtures.finding("FindingOpened", VM, "f-1", 2, "warning", T0.plusSeconds(2))).block();
        assertThat(index.find(scope, VM).block().drift()).isTrue();
        state.forget(VM);
        indexing.refetch(scope, VM).block();
        assertThat(index.find(scope, VM).blockOptional()).isEmpty();
    }

    @Test
    void aTombstoneOfTheStateServiceKeepsTheName() {
        handler.handle(Fixtures.requested(VM, "web-03", Map.of())).block();
        state.tombstone(VM, 5, T0.plusSeconds(9));
        indexing.apply(Fixtures.tagged(VM, 4, Map.of("a", "b"), List.of(), T0.plusSeconds(4))).block();
        IndexedResource doc = index.find(scope, VM).block();
        assertThat(doc.state()).isEqualTo(ResourceState.DELETED);
        assertThat(doc.name()).isEqualTo("web-03");
        assertThat(doc.version()).isEqualTo(5);
        assertThat(doc.tombstoneUntil()).isNotNull();
    }

    @Test
    void anEventOfAnotherKindIsIgnored() {
        var e = com.iaf.console.kit.events.EventEnvelope.of("e", "UserChanged:1:2:3", "UserChanged", T0, "user", "u-1", "x", Fixtures.data().put("version", 1));
        assertThat(indexing.apply(e).block()).isInstanceOf(EventOutcome.Ignored.class);
    }
}
