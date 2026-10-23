package com.iaf.console.drift.rightsizing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.drift.TestClock;
import com.iaf.console.drift.drift.adapters.memory.InMemoryIdempotencyKeys;
import com.iaf.console.drift.drift.adapters.memory.InMemoryWatchStore;
import com.iaf.console.drift.drift.application.IdempotentCommands;
import com.iaf.console.drift.drift.application.InventorySync;
import com.iaf.console.drift.drift.domain.Decision;
import com.iaf.console.drift.drift.domain.Json;
import com.iaf.console.drift.drift.domain.Money;
import com.iaf.console.drift.drift.domain.StateResource;
import com.iaf.console.drift.drift.domain.TypeInfo;
import com.iaf.console.drift.drift.domain.Watch;
import com.iaf.console.drift.drift.ports.outbound.OnBehalfOfPort;
import com.iaf.console.drift.drift.ports.outbound.ResourceTypesPort;
import com.iaf.console.drift.drift.ports.outbound.StateServicePort;
import com.iaf.console.drift.drift.ports.outbound.WorkflowStarterPort;
import com.iaf.console.drift.rightsizing.adapters.memory.InMemoryRecommendationStore;
import com.iaf.console.drift.rightsizing.domain.Recommendation;
import com.iaf.console.drift.rightsizing.domain.RecommendationAction;
import com.iaf.console.drift.rightsizing.domain.RecommendationKind;
import com.iaf.console.drift.rightsizing.domain.RecommendationStatus;
import com.iaf.console.drift.rightsizing.domain.RightsizingRules;
import com.iaf.console.drift.rightsizing.domain.Utilisation;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.platform.client.PlatformPort;
import com.iaf.console.platform.client.RequestOptions;
import com.iaf.console.platform.model.CatalogEntry;
import com.iaf.console.platform.model.JobAccepted;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Applying and dismissing a recommendation: the change recorded at the state service before the
 * job starts on behalf of the person, the input and labels the job gets, and every refusal the
 * contract names (closed, changing, not applicable, no person).
 */
class RecommendationServiceTest {

    static final String ACCOUNT = "123456789012";
    static final AccountScope SCOPE = AccountScope.of(ACCOUNT, "global", "dana");
    static final Instant T0 = Instant.parse("2026-10-23T12:00:00Z");
    static final OnBehalfOfPort.Person DANA = new OnBehalfOfPort.Person("dana", null, null, List.of("developer"));
    static final TypeInfo VM = new TypeInfo("vm", "vms", "compute", "vsphere", "vm.get", Map.of(), Set.of(), Duration.ofMinutes(15), "missing", false,
            false, Set.of("cpu", "memoryGb"), List.of("cpu", "memoryGb"), new TypeInfo.Metrics("vm.metrics", List.of("cpuPercent", "memoryPercent")),
            Map.of());

    /** The workflows the catalog lists for VMs: the update sets CPU and memory, both take a reason. */
    static CatalogEntry entry(String workflow, String verb, String... fields) {
        ObjectNode properties = Json.object();
        properties.putObject("vmId").put("type", "string");
        properties.putObject("reason").put("type", "string");
        for (String field : fields) {
            properties.putObject(field).put("type", "integer");
        }
        ObjectNode node = Json.object();
        node.put("workflow", workflow);
        node.put("noun", "vm");
        node.put("verb", verb);
        node.put("method", "POST");
        node.put("path", "/api/v1/vms/{vmId}:" + verb);
        node.put("version", "1.2.0");
        node.put("subjectInput", "vmId");
        node.putObject("input").put("type", "object").set("properties", properties);
        return Json.MAPPER.convertValue(node, CatalogEntry.class);
    }

    record Started(String accountId, CatalogEntry entry, Map<String, String> pathParams, Map<String, Object> input, RequestOptions options) {
    }

    private final TestClock clock = new TestClock(T0);
    private final InMemoryRecommendationStore store = new InMemoryRecommendationStore();
    private final InMemoryWatchStore watches = new InMemoryWatchStore();
    private final PlatformPort platform = mock(PlatformPort.class);
    private final StateServicePort state = mock(StateServicePort.class);
    private final InventorySync sync = mock(InventorySync.class);
    private final List<Started> started = new ArrayList<>();
    private RecommendationService service;
    private String resizeId;
    private String deleteId;

    static String wfrn(String id) {
        return "wfrn:wf:compute:global:" + ACCOUNT + ":vm/" + id;
    }

    static ObjectNode size(int cpu, int memoryGb) {
        return Json.object().put("cpu", cpu).put("memoryGb", memoryGb);
    }

    static StateResource resource(String id, String lifecycle) {
        ObjectNode node = Json.object();
        node.put("wfrn", wfrn(id));
        node.put("accountId", ACCOUNT);
        node.put("region", "global");
        node.put("type", "vm");
        node.put("state", lifecycle);
        node.put("version", 2);
        return Json.MAPPER.convertValue(node, StateResource.class);
    }

    @BeforeEach
    void setUp() {
        ResourceTypesPort types = new ResourceTypesPort() {
            @Override
            public Mono<TypeInfo> find(String name) {
                return Mono.justOrEmpty("vm".equals(name) ? VM : null);
            }

            @Override
            public Flux<TypeInfo> list() {
                return Flux.just(VM);
            }
        };
        WorkflowStarterPort starter = (accountId, entry, pathParams, input, options) -> Mono.fromSupplier(() -> {
            started.add(new Started(accountId, entry, pathParams, input, options));
            return new JobAccepted("job-" + started.size(), "queued", Map.of());
        });
        OnBehalfOfPort onBehalfOf = (accountId, region, person) -> Mono.just("signed-for-" + person.subject());
        when(platform.listCatalog("vm")).thenReturn(Mono.just(List.of(entry("update-vm", "update", "cpu", "memoryGb"), entry("delete-vm", "delete"))));
        when(state.requestUpdate(anyString(), anyString(), any(), any(), anyString(), any())).thenReturn(Mono.just(resource("big", "updating")));
        when(state.requestDelete(anyString(), anyString(), any(), anyString(), any())).thenReturn(Mono.just(resource("idle", "deleting")));
        when(state.noteJob(anyString(), anyString())).thenReturn(Mono.empty());
        when(sync.upsert(any())).thenReturn(Mono.empty());
        when(sync.refresh(anyString())).thenReturn(Mono.empty());
        service = new RecommendationService(store, watches, types, platform, state, starter, onBehalfOf, sync,
                new IdempotentCommands(new InMemoryIdempotencyKeys(clock), Duration.ofHours(24), clock), "cpu", "memoryGb", clock);

        watches.save(Watch.builder(wfrn("big"), ACCOUNT, "global", "vm").name("big").registryId("vm-big").desired(1, size(4, 16)).build()).block();
        watches.save(Watch.builder(wfrn("idle"), ACCOUNT, "global", "vm").name("idle").registryId("vm-idle").desired(1, size(2, 8)).build()).block();
        Utilisation used = new Utilisation(T0.minus(Duration.ofDays(14)), T0, 336, 3.0, 4.0, 6.0, 7.0);
        resizeId = store.save(Recommendation.open("rec-big", wfrn("big"), ACCOUNT, "global", "vm", "big",
                new RightsizingRules.Verdict(RecommendationKind.OVERSIZED, RecommendationAction.RESIZE, size(4, 16), size(2, 8)), used,
                Money.of(100, "USD", null), Money.of(40, "USD", null), T0)).block().recommendationId();
        deleteId = store.save(Recommendation.open("rec-idle", wfrn("idle"), ACCOUNT, "global", "vm", "idle",
                new RightsizingRules.Verdict(RecommendationKind.IDLE, RecommendationAction.DELETE, size(2, 8), null), used,
                Money.of(60, "USD", null), Money.of(60, "USD", null), T0)).block().recommendationId();
    }

    private Recommendation stored(String id) {
        return store.findAny(id).block();
    }

    private Throwable refusal(String id, Decision decision, OnBehalfOfPort.Person person) {
        return catchThrowable(() -> service.apply(SCOPE, id, decision, null, null, null, person).block());
    }

    @Test
    void aResizeIsRecordedThenStartedForThePersonAndAppliedAtOnce() {
        IdempotentCommands.Outcome outcome = service.apply(SCOPE, resizeId, new Decision("trim it", null), "key-1", "req-1", null, DANA).block();
        assertThat(outcome.status()).isEqualTo(202);
        assertThat(outcome.replay()).isFalse();
        assertThat(outcome.body().path("recommendationId").asText()).isEqualTo(resizeId);
        assertThat(outcome.body().path("jobId").asText()).isEqualTo("job-1");

        verify(state).requestUpdate(eq(wfrn("big")), eq("update-vm"), eq("1.2.0"), eq(size(2, 8)), eq("dana"), eq("req-1"));
        verify(state).noteJob(wfrn("big"), "job-1");
        verify(sync).upsert(any());
        Started job = started.get(0);
        assertThat(job.accountId()).isEqualTo(ACCOUNT);
        assertThat(job.entry().workflow()).isEqualTo("update-vm");
        assertThat(job.pathParams()).containsExactly(Map.entry("vmId", "vm-big"));
        assertThat(job.input()).containsEntry("vmId", "vm-big").containsEntry("cpu", 2).containsEntry("memoryGb", 8).containsEntry("reason", "trim it");
        assertThat(job.options().onBehalfOf()).isEqualTo("signed-for-dana");
        assertThat(job.options().labels()).containsExactly(Map.entry(RecommendationService.LABEL, resizeId));
        assertThat(job.options().resourceWfrn()).isEqualTo(wfrn("big"));
        assertThat(job.options().workflowVersion()).isEqualTo("1.2.0");
        assertThat(job.options().idempotencyKey()).startsWith("rightsizing:").doesNotContain("key-1");

        Recommendation applied = stored(resizeId);
        assertThat(applied.status()).isEqualTo(RecommendationStatus.APPLIED);
        assertThat(applied.jobId()).isEqualTo("job-1");
        assertThat(applied.decidedBy()).isEqualTo("dana");
        assertThat(applied.reason()).isEqualTo("trim it");

        // the same key and body answer the first result without a second job
        IdempotentCommands.Outcome replay = service.apply(SCOPE, resizeId, new Decision("trim it", null), "key-1", "req-1", null, DANA).block();
        assertThat(replay.replay()).isTrue();
        assertThat(replay.body()).isEqualTo(outcome.body());
        assertThat(started).hasSize(1);
        assertThat(refusal(resizeId, Decision.NONE, DANA)).isInstanceOf(ConflictException.class).hasMessageStartingWith("recommendation.closed");
    }

    @Test
    void aDeleteRecordsTheDeleteRequestAndRelaysTheBffsAssertion() {
        IdempotentCommands.Outcome outcome = service.apply(SCOPE, deleteId, Decision.NONE, null, null, "relayed-assertion", null).block();
        assertThat(outcome.body().path("jobId").asText()).isEqualTo("job-1");
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(state).requestDelete(eq(wfrn("idle")), eq("delete-vm"), reason.capture(), eq("dana"), isNull());
        assertThat(reason.getValue()).startsWith("rightsizing recommendation " + deleteId).contains("idle").hasSizeGreaterThanOrEqualTo(10);
        verify(state, never()).requestUpdate(anyString(), anyString(), any(), any(), anyString(), any());
        Started job = started.get(0);
        assertThat(job.entry().workflow()).isEqualTo("delete-vm");
        assertThat(job.input()).containsOnlyKeys("vmId", "reason");
        assertThat(job.options().onBehalfOf()).isEqualTo("relayed-assertion");
        assertThat(job.options().idempotencyKey()).isEqualTo("rightsizing:" + deleteId);
        assertThat(stored(deleteId).status()).isEqualTo(RecommendationStatus.APPLIED);
        assertThat(stored(deleteId).decidedBy()).isEqualTo("dana");
    }

    @Test
    void withoutAPersonOrAnAssertionItIsRefused() {
        assertThat(refusal(resizeId, Decision.NONE, null)).isInstanceOf(ConflictException.class).hasMessageStartingWith("revert.needsPerson");
        assertThat(started).isEmpty();
        assertThat(stored(resizeId).status()).isEqualTo(RecommendationStatus.OPEN);
    }

    @Test
    void aChangeInFlightIsRefusedAndTheRecommendationStaysOpen() {
        Watch big = watches.find(wfrn("big")).block();
        watches.save(big.toBuilder().state("updating").build()).block();
        assertThat(refusal(resizeId, Decision.NONE, DANA)).isInstanceOf(ConflictException.class).hasMessageStartingWith("recommendation.changing");
        assertThat(stored(resizeId).status()).isEqualTo(RecommendationStatus.OPEN);

        // the state service may know of a change the watch has not seen yet
        when(state.requestDelete(anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(Mono.error(new StateServicePort.StateError(409, "resource.busy", null)));
        assertThat(refusal(deleteId, Decision.NONE, DANA)).isInstanceOf(ConflictException.class).hasMessageStartingWith("recommendation.changing");
        assertThat(started).isEmpty();
        assertThat(stored(deleteId).status()).isEqualTo(RecommendationStatus.OPEN);
    }

    @Test
    void aResourceThatChangedSizeOrLeftSupersedesTheRecommendation() {
        Watch big = watches.find(wfrn("big")).block();
        watches.save(big.toBuilder().desired(2, size(8, 16)).build()).block();
        assertThat(refusal(resizeId, Decision.NONE, DANA)).isInstanceOf(ConflictException.class).hasMessageStartingWith("recommendation.closed");
        assertThat(stored(resizeId).status()).isEqualTo(RecommendationStatus.SUPERSEDED);

        watches.delete(wfrn("idle")).block();
        assertThat(refusal(deleteId, Decision.NONE, DANA)).isInstanceOf(ConflictException.class).hasMessageStartingWith("recommendation.closed");
        assertThat(stored(deleteId).status()).isEqualTo(RecommendationStatus.SUPERSEDED);
        assertThat(started).isEmpty();
    }

    @Test
    void aTypeWithoutTheWorkflowOrAFieldItsUpdateDoesNotSetIsNotApplicable() {
        when(platform.listCatalog("vm")).thenReturn(Mono.just(List.of(entry("update-vm", "update", "cpu"))));
        Throwable stuck = refusal(resizeId, Decision.NONE, DANA);
        assertThat(stuck).isInstanceOf(ConflictException.class).hasMessageStartingWith("recommendation.notApplicable").hasMessageContaining("memoryGb");
        assertThat(refusal(deleteId, Decision.NONE, DANA)).isInstanceOf(ConflictException.class)
                .hasMessageStartingWith("recommendation.notApplicable").hasMessageContaining("no delete workflow");
        assertThat(started).isEmpty();
        assertThat(stored(resizeId).status()).isEqualTo(RecommendationStatus.OPEN);
    }

    @Test
    void anUnknownRecommendationOrAnOverlongReasonIsRefused() {
        assertThat(refusal("rec-none", Decision.NONE, DANA)).isInstanceOf(NotFoundException.class);
        assertThat(catchThrowable(() -> service.apply(AccountScope.of("210987654321", "global", "ana"), resizeId, Decision.NONE, null, null, null, DANA)
                .block())).as("another account's").isInstanceOf(NotFoundException.class);
        assertThat(refusal(resizeId, new Decision("x".repeat(1001), null), DANA)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dismissingSetsItAsideForGoodOrUntilADate() {
        assertThat(catchThrowable(() -> service.dismiss(SCOPE, resizeId, Decision.NONE, T0.minusSeconds(1), null).block()))
                .isInstanceOf(IllegalArgumentException.class);
        IdempotentCommands.Outcome forGood = service.dismiss(SCOPE, resizeId, new Decision("we need the headroom", "u-77"), null, "d-1").block();
        assertThat(forGood.status()).isEqualTo(200);
        assertThat(forGood.body().path("status").asText()).isEqualTo("dismissed");
        assertThat(forGood.body().path("decidedBy").asText()).isEqualTo("u-77");
        assertThat(forGood.body().has("dismissedUntil")).isFalse();
        assertThat(service.dismiss(SCOPE, resizeId, new Decision("we need the headroom", "u-77"), null, "d-1").block().replay()).isTrue();
        assertThat(catchThrowable(() -> service.dismiss(SCOPE, resizeId, Decision.NONE, null, null).block())).isInstanceOf(ConflictException.class)
                .hasMessageStartingWith("recommendation.closed");

        Instant until = T0.plus(Duration.ofDays(30));
        service.dismiss(SCOPE, deleteId, Decision.NONE, until, null).block();
        Recommendation later = stored(deleteId);
        assertThat(later.dismissedUntil()).isEqualTo(until);
        assertThat(later.decidedBy()).as("the principal, when the BFF names no one").isEqualTo("dana");
        assertThat(refusal(deleteId, Decision.NONE, DANA)).isInstanceOf(ConflictException.class).hasMessageStartingWith("recommendation.closed");
    }
}
