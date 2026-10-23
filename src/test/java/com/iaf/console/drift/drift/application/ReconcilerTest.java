package com.iaf.console.drift.drift.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.drift.TestClock;
import com.iaf.console.drift.drift.adapters.memory.InMemoryFindingStore;
import com.iaf.console.drift.drift.adapters.memory.InMemoryWatchStore;
import com.iaf.console.drift.drift.domain.Finding;
import com.iaf.console.drift.drift.domain.FindingField;
import com.iaf.console.drift.drift.domain.FindingStatus;
import com.iaf.console.drift.drift.domain.Json;
import com.iaf.console.drift.drift.domain.Observation;
import com.iaf.console.drift.drift.domain.Resolution;
import com.iaf.console.drift.drift.domain.Severity;
import com.iaf.console.drift.drift.domain.StateResource;
import com.iaf.console.drift.drift.domain.Tier;
import com.iaf.console.drift.drift.domain.TierRules;
import com.iaf.console.drift.drift.domain.TypeInfo;
import com.iaf.console.drift.drift.domain.Watch;
import com.iaf.console.drift.drift.ports.outbound.StateServicePort;
import com.iaf.console.kit.outbox.InMemoryOutbox;
import com.iaf.console.kit.outbox.OutboxEntry;
import com.iaf.console.kit.paging.Page;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import reactor.core.publisher.Mono;

class ReconcilerTest {

    static final String WFRN = "wfrn:wf:compute:global:123456789012:vm/vm-01";
    static final TypeInfo VM = new TypeInfo("vm", "vms", "compute", "vsphere", "vm.get", Map.of(), Set.of("owner"), Duration.ofMinutes(15), "missing",
            false, true, Set.of("cpu", "memoryGb"), List.of());

    private final TestClock clock = new TestClock(Instant.parse("2026-10-23T12:00:00Z"));
    private final InMemoryFindingStore findings = new InMemoryFindingStore();
    private final InMemoryWatchStore watches = new InMemoryWatchStore();
    private final InMemoryOutbox outbox = new InMemoryOutbox();
    private final RecordingState state = new RecordingState();
    private Reconciler reconciler;

    @BeforeEach
    void wire() {
        reconciler = new Reconciler(findings, watches, state, new FindingEvents(outbox, clock), TierRules.defaults(), Reconciler.Timing.DEFAULT,
                new DriftMetrics(new SimpleMeterRegistry()), clock);
    }

    static ObjectNode attrs(int cpu, int memoryGb) {
        ObjectNode n = Json.object();
        n.put("cpu", cpu);
        n.put("memoryGb", memoryGb);
        n.put("owner", "team-compute");
        return n;
    }

    /** The resource's watch as the sync writes it, over whatever the store holds. */
    private Watch watch() {
        Watch base = Watch.builder(WFRN, "123456789012", "global", "vm").environment("dev").desired(3, attrs(2, 8))
                .resourceUpdatedAt(clock.instant().minus(Duration.ofDays(3))).build();
        long rev = watches.find(WFRN).map(Watch::rev).defaultIfEmpty(0L).block();
        return stored(base.withRev(rev));
    }

    private Watch stored(Watch w) {
        return watches.save(w).block();
    }

    private Observation read(int cpu, int memoryGb) {
        ObjectNode observed = attrs(cpu, memoryGb);
        observed.put("owner", "someone-else");
        return Observation.found(observed, clock.instant(), "vsphere", "vm.get");
    }

    private Reconciler.Outcome apply(Watch w, Observation o) {
        return reconciler.apply(w, VM, o).block();
    }

    private List<String> eventTypes() {
        return outbox.all().map(OutboxEntry::event).map(e -> e.type()).collectList().block();
    }

    @Test
    void driftOpensOneFindingTellsTheStateAndPublishes() {
        Reconciler.Outcome first = apply(watch(), read(2, 32));
        assertThat(first.action()).isEqualTo(Reconciler.Outcome.OPENED);
        Finding f = first.finding();
        assertThat(f.status()).isEqualTo(FindingStatus.OPEN);
        assertThat(f.severity()).isEqualTo(Severity.WARNING);
        assertThat(f.drifted()).extracting(FindingField::path).containsExactly("/memoryGb");
        assertThat(first.watch().openFindingId()).isEqualTo(f.findingId());
        assertThat(first.watch().tier()).isEqualTo(Tier.HOT);
        assertThat(state.calls).contains("observation:" + WFRN, "drift:" + WFRN + ":" + f.findingId());
        assertThat(eventTypes()).containsExactly(FindingEvents.OPENED);

        // the same read again: no new version, nothing published, the state not told again
        state.calls.clear();
        clock.advance(Duration.ofMinutes(15));
        Reconciler.Outcome again = apply(first.watch(), read(2, 32));
        assertThat(again.action()).isEqualTo(Reconciler.Outcome.NONE);
        assertThat(again.finding().version()).isEqualTo(1);
        assertThat(eventTypes()).containsExactly(FindingEvents.OPENED);
        assertThat(state.calls).noneMatch(c -> c.startsWith("drift:"));

        // another value: a new version
        clock.advance(Duration.ofMinutes(15));
        Reconciler.Outcome changed = apply(again.watch(), read(4, 32));
        assertThat(changed.action()).isEqualTo(Reconciler.Outcome.UPDATED);
        assertThat(changed.finding().version()).isEqualTo(2);
        assertThat(eventTypes()).containsExactly(FindingEvents.OPENED, FindingEvents.UPDATED);
    }

    @Test
    void aResourceBackInLineClosesItsFindingAsVanished() {
        Reconciler.Outcome opened = apply(watch(), read(2, 32));
        clock.advance(Duration.ofMinutes(15));
        Reconciler.Outcome closed = apply(opened.watch(), read(2, 8));
        assertThat(closed.action()).isEqualTo(Reconciler.Outcome.CLOSED);
        assertThat(closed.finding().resolution()).isEqualTo(Resolution.VANISHED);
        assertThat(closed.watch().openFindingId()).isNull();
        assertThat(state.calls).contains("resolve:" + WFRN + ":" + opened.finding().findingId() + ":vanished");
        assertThat(eventTypes()).containsExactly(FindingEvents.OPENED, FindingEvents.CLOSED);
    }

    @Test
    void aFindingWithARevertRunningClosesAsReverted() {
        Reconciler.Outcome opened = apply(watch(), read(2, 32));
        findings.save(opened.finding().reverting("job-1", "dana", "put it back", clock.instant())).block();
        clock.advance(Duration.ofMinutes(1));
        Reconciler.Outcome closed = apply(opened.watch(), read(2, 8));
        assertThat(closed.finding().resolution()).isEqualTo(Resolution.REVERTED);
        assertThat(closed.finding().decidedBy()).isEqualTo("dana");
    }

    @Test
    void aChangeInFlightAndAFailedReadLeaveTheRecordAlone() {
        Watch updating = stored(watch().toBuilder().state("updating").build());
        Reconciler.Outcome skipped = apply(updating, read(8, 64));
        assertThat(skipped.action()).isEqualTo(Reconciler.Outcome.SKIPPED);
        assertThat(skipped.watch().nextCheckAt()).isEqualTo(clock.instant().plus(Reconciler.Timing.DEFAULT.inFlightRetry()));

        Reconciler.Outcome failed = apply(watch(), Observation.error("vsphere timed out", clock.instant(), "vsphere", "vm.get"));
        assertThat(failed.action()).isEqualTo(Reconciler.Outcome.ERROR);
        assertThat(failed.watch().nextCheckAt()).isEqualTo(clock.instant().plus(Reconciler.Timing.DEFAULT.errorRetry()));
        assertThat(findings.all()).isEmpty();
        assertThat(eventTypes()).isEmpty();
    }

    @Test
    void aMissingResourceIsACriticalFinding() {
        Reconciler.Outcome missing = apply(watch(), Observation.notFound(clock.instant(), "vsphere", "vm.get"));
        assertThat(missing.action()).isEqualTo(Reconciler.Outcome.OPENED);
        assertThat(missing.finding().severity()).isEqualTo(Severity.CRITICAL);
        assertThat(missing.finding().fields()).extracting(FindingField::path).containsExactly(FindingField.WHOLE);
    }

    @Test
    void aWriteTheStoreRefusedIsNeitherRecordedNorPublished() {
        Reconciler.Outcome opened = apply(watch(), read(2, 32));
        // someone accepted the finding meanwhile: the store holds it closed
        Finding accepted = opened.finding().closed(Resolution.ACCEPTED, "dana", "on purpose", clock.instant(), null);
        findings.save(accepted).block();
        // a scan that still saw it open reads the resource back in line
        Reconciler stale = new Reconciler(new StaleOpen(findings, opened.finding()), watches, state, new FindingEvents(outbox, clock),
                TierRules.defaults(), Reconciler.Timing.DEFAULT, new DriftMetrics(new SimpleMeterRegistry()), clock);
        state.calls.clear();
        Reconciler.Outcome outcome = stale.apply(opened.watch(), VM, read(2, 8)).block();
        assertThat(outcome.action()).isEqualTo(Reconciler.Outcome.NONE);
        assertThat(outcome.finding().resolution()).isEqualTo(Resolution.ACCEPTED);
        assertThat(state.calls).noneMatch(c -> c.startsWith("resolve:"));
        assertThat(eventTypes()).containsExactly(FindingEvents.OPENED);
    }

    @Test
    void aScanHoldingAnOldCopyDecidesAgainOnTheWatchAsItStands() {
        Reconciler.Outcome opened = apply(watch(), read(2, 32));
        Watch old = opened.watch();
        // an accept meanwhile: the observed memory is the desired one now, and the finding is closed
        stored(old.toBuilder().desired(4, attrs(2, 32)).openFindingId(null).build());
        findings.save(opened.finding().closed(Resolution.ACCEPTED, "dana", "on purpose", clock.instant(), null)).block();
        clock.advance(Duration.ofMinutes(1));

        // the scan read the watch before the accept: it must not open a finding against the old desired state
        Reconciler.Outcome outcome = apply(old, read(2, 32));
        assertThat(outcome.action()).isEqualTo(Reconciler.Outcome.NONE);
        assertThat(outcome.watch().desiredVersion()).isEqualTo(4);
        assertThat(outcome.watch().openFindingId()).isNull();
        assertThat(findings.all()).singleElement().extracting(Finding::resolution).isEqualTo(Resolution.ACCEPTED);
        assertThat(eventTypes()).containsExactly(FindingEvents.OPENED);
    }

    @Test
    void aWatchThatLeftTheInventoryMeanwhileIsLeftAlone() {
        Watch w = watch();
        watches.delete(WFRN).block();
        Reconciler.Outcome outcome = apply(w, read(2, 32));
        assertThat(outcome.action()).isEqualTo(Reconciler.Outcome.SKIPPED);
        assertThat(findings.all()).isEmpty();
        assertThat(watches.find(WFRN).block()).isNull();
    }

    @Test
    void watchesSaveOnlyOverTheRevisionTheyRead() {
        Watch first = watch();
        assertThat(first.rev()).isEqualTo(1);
        Watch second = stored(first.toBuilder().tier(Tier.WARM).build());
        assertThat(second.rev()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> stored(first.toBuilder().tier(Tier.COLD).build()))
                .isInstanceOf(com.iaf.console.drift.drift.ports.outbound.WatchStorePort.Conflict.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> stored(first.withRev(0)))
                .isInstanceOf(com.iaf.console.drift.drift.ports.outbound.WatchStorePort.Conflict.class);
        assertThat(watches.find(WFRN).block().tier()).isEqualTo(Tier.WARM);
    }

    @Test
    void oneFindingIsOpenPerResource() {
        Finding first = Finding.open("fnd-a", WFRN, "123456789012", "global", "vm", Severity.INFO, List.of(), clock.instant());
        Finding second = Finding.open("fnd-b", WFRN, "123456789012", "global", "vm", Severity.INFO, List.of(), clock.instant());
        assertThat(findings.save(first).block().findingId()).isEqualTo("fnd-a");
        assertThat(findings.save(second).block().findingId()).isEqualTo("fnd-a");
        assertThat(findings.all()).hasSize(1);
    }

    /** A store whose lookup answers a finding as it was before another writer closed it. */
    record StaleOpen(InMemoryFindingStore delegate, Finding staleOpen) implements com.iaf.console.drift.drift.ports.outbound.FindingStorePort {
        @Override
        public Mono<Finding> find(com.iaf.console.kit.scope.AccountScope scope, String findingId) {
            return delegate.find(scope, findingId);
        }

        @Override
        public Mono<Page<Finding>> list(com.iaf.console.kit.scope.AccountScope scope, com.iaf.console.drift.drift.domain.FindingQuery query) {
            return delegate.list(scope, query);
        }

        @Override
        public Mono<com.iaf.console.drift.drift.domain.FindingsSummary> countOpen(com.iaf.console.kit.scope.AccountScope scope, String region) {
            return delegate.countOpen(scope, region);
        }

        @Override
        public Mono<Finding> findOpenByWfrn(String wfrn) {
            return Mono.just(staleOpen);
        }

        @Override
        public reactor.core.publisher.Flux<Finding> findReverting(int limit) {
            return delegate.findReverting(limit);
        }

        @Override
        public Mono<Finding> findAny(String findingId) {
            return delegate.findAny(findingId);
        }

        @Override
        public Mono<Finding> save(Finding finding) {
            return delegate.save(finding);
        }
    }

    /** The state service, as calls it was asked to make. */
    static final class RecordingState implements StateServicePort {
        final List<String> calls = new CopyOnWriteArrayList<>();

        @Override
        public Mono<Page<StateResource>> listResources(String accountId, Instant updatedAfter, String after, int limit) {
            return Mono.just(new Page<>(List.of(), null));
        }

        @Override
        public Mono<StateResource> getResource(String wfrn) {
            return Mono.empty();
        }

        @Override
        public Mono<StateResource> recordObservation(String wfrn, Observation observation, String actor) {
            calls.add("observation:" + wfrn);
            return Mono.empty();
        }

        @Override
        public Mono<StateResource> recordDrift(String wfrn, String findingId, Severity severity, List<FindingField> fields, String actor) {
            calls.add("drift:" + wfrn + ":" + findingId);
            return Mono.empty();
        }

        @Override
        public Mono<StateResource> resolveDrift(String wfrn, String findingId, Resolution resolution, String actor) {
            calls.add("resolve:" + wfrn + ":" + findingId + ":" + resolution.id());
            return Mono.empty();
        }

        @Override
        public Mono<StateResource> requestUpdate(String wfrn, String workflow, String workflowVersion, ObjectNode changes, String actor,
                String requestId) {
            calls.add("update:" + wfrn);
            return Mono.empty();
        }

        @Override
        public Mono<StateResource> importResource(ImportCommand command) {
            calls.add("import:" + command.wfrn());
            return Mono.empty();
        }
    }
}
