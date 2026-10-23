package com.iaf.console.state.inventory.application;

import static com.iaf.console.state.inventory.domain.Fixtures.ACCOUNT;
import static com.iaf.console.state.inventory.domain.Fixtures.REGION;
import static com.iaf.console.state.inventory.domain.Fixtures.T0;
import static com.iaf.console.state.inventory.domain.Fixtures.by;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.kit.idempotency.IdempotentConsumer;
import com.iaf.console.kit.idempotency.InMemoryProcessedEvents;
import com.iaf.console.kit.outbox.InMemoryOutbox;
import com.iaf.console.kit.outbox.OutboxEntry;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.platform.client.PagedList;
import com.iaf.console.platform.client.PlatformPort;
import com.iaf.console.platform.model.FeedEvent;
import com.iaf.console.platform.model.JobContext;
import com.iaf.console.platform.model.JobStatus;
import com.iaf.console.platform.model.JobSummary;
import com.iaf.console.platform.model.ResourceOrigin;
import com.iaf.console.state.TestClock;
import com.iaf.console.state.inventory.adapters.memory.InMemoryAccountKeys;
import com.iaf.console.state.inventory.adapters.memory.InMemoryResourceEventStore;
import com.iaf.console.state.inventory.adapters.memory.InMemoryResourceProjection;
import com.iaf.console.state.inventory.domain.Commands;
import com.iaf.console.state.inventory.domain.Fixtures;
import com.iaf.console.state.inventory.domain.Json;
import com.iaf.console.state.inventory.domain.Resource;
import com.iaf.console.state.inventory.domain.ResourceState;
import com.iaf.console.state.inventory.domain.ResourceTypeInfo;
import com.iaf.console.state.inventory.ports.outbound.ResourceTypesPort;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The platform's feed drives the lifecycle: the feed says that something changed, the registry says
 * what. Covers the handler, the job outcomes and the reaper against a mocked platform.
 */
class IngestionTest {

    private static final AccountScope SCOPE = AccountScope.of(ACCOUNT, REGION, "dana");

    private final TestClock clock = new TestClock(T0);
    private final InMemoryResourceEventStore events = new InMemoryResourceEventStore();
    private final InMemoryResourceProjection projection = new InMemoryResourceProjection();
    private final InMemoryOutbox outbox = new InMemoryOutbox();
    private final InventoryMetrics metrics = new InventoryMetrics(new SimpleMeterRegistry());
    private final PlatformPort platform = mock(PlatformPort.class, withSettings().defaultAnswer(emptyPublishers()));
    private final ResourceTypesPort types = new ResourceTypesPort() {
        @Override
        public Mono<ResourceTypeInfo> find(String name) {
            return switch (name) {
                case "vm" -> Mono.just(Fixtures.VM);
                case "database" -> Mono.just(Fixtures.DATABASE);
                default -> Mono.empty();
            };
        }

        @Override
        public Flux<ResourceTypeInfo> list() {
            return Flux.just(Fixtures.VM, Fixtures.DATABASE);
        }
    };
    private final InventoryService inventory = new InventoryService(new ResourceLoader(events, projection), events, projection, types,
            new SensitiveData(new InMemoryAccountKeys(), SensitiveData.randomMasterKey(), clock), new ResourceEventPublisher(outbox), metrics, clock,
            "wf", Duration.ofDays(90));
    private final PlatformResources registry = new PlatformResources(platform);
    private final JobOutcomes outcomes = new JobOutcomes(inventory, registry);
    private final PlatformEventHandler handler = new PlatformEventHandler(inventory, outcomes, registry,
            new IdempotentConsumer(new InMemoryProcessedEvents(), clock), metrics, REGION);
    private final PendingReaper reaper = new PendingReaper(projection, platform, inventory, outcomes, registry, metrics, clock, Duration.ofMinutes(5),
            Duration.ofHours(2));

    private static Answer<Object> emptyPublishers() {
        return invocation -> {
            Class<?> returned = invocation.getMethod().getReturnType();
            if (returned == Mono.class) {
                return Mono.empty();
            }
            if (returned == Flux.class) {
                return Flux.empty();
            }
            return null;
        };
    }

    // the platform's side

    private static com.iaf.console.platform.model.Resource record(String id, String wfrn, String jobId, String status, String name) {
        return new com.iaf.console.platform.model.Resource(id, "vm", name, status, Map.of("name", name, "cpu", 2, "memoryGb", 4, "environment", "dev"),
                "team-compute", "team", new ResourceOrigin(jobId, "create-vm", "2.0.1"), List.of("img-1"), T0, T0, wfrn, ACCOUNT, REGION, "vsphere-" + id,
                null);
    }

    private static FeedEvent feed(long seq, String type, String aggregate, String aggregateId, String wfrn, ObjectNode data) {
        JobContext context = wfrn == null ? null : new JobContext(ACCOUNT, REGION, wfrn, "req_" + seq, Map.of());
        return new FeedEvent("1.0", 1, "evt_" + seq, type + ":" + aggregateId + ":" + seq, seq, type, T0.plusSeconds(seq), aggregate, aggregateId,
                "svc:platform", null, context, null, data);
    }

    private static ObjectNode registered(com.iaf.console.platform.model.Resource r, String jobId) {
        return Json.object().put("id", r.id()).put("type", r.type()).put("state", r.status()).put("jobId", jobId).put("workflow", "create-vm")
                .put("by", "platform").put("wfrn", r.wfrn()).put("accountId", r.accountId()).put("region", r.region()).put("providerId", r.providerId());
    }

    private static ObjectNode finished(String jobId, String status, String errorClass) {
        ObjectNode data = Json.object().put("jobId", jobId).put("workflow", "create-vm").put("version", "2.0.1").put("status", status);
        if (errorClass != null) {
            data.put("errorClass", errorClass).put("failedStep", "createVm").put("message", "the provider said no");
        }
        return data;
    }

    private Resource mint(String name) {
        return inventory.mint(SCOPE, new Commands.Mint("vm", REGION, name, "create-vm", Fixtures.input(name), by("dana"))).block();
    }

    private Resource current(String wfrn) {
        return inventory.current(SCOPE, wfrn).block();
    }

    @Test
    void aConsoleRequestBecomesActiveThroughTheFeedAndTheRegistry() {
        Resource pending = mint("web-03");
        String wfrn = pending.wfrn();
        assertThat(pending.state()).isEqualTo(ResourceState.PENDING);

        handler.handle(feed(1, "job.started", "job", "job_1", wfrn, Json.object().put("jobId", "job_1").put("workflow", "create-vm").put("version", "2.0.1")))
                .block();
        assertThat(current(wfrn).lastJobId()).isEqualTo("job_1");
        assertThat(current(wfrn).state()).isEqualTo(ResourceState.PENDING);

        com.iaf.console.platform.model.Resource record = record("vm-7", wfrn, "job_1", "ready", "web-03");
        when(platform.getResourceByWfrn(wfrn)).thenReturn(Mono.just(record));
        FeedEvent registration = feed(2, "resource.registered", "resource", "vm-7", null, registered(record, "job_1"));
        handler.handle(registration).block();
        Resource active = current(wfrn);
        assertThat(active.state()).isEqualTo(ResourceState.ACTIVE);
        assertThat(active.version()).isEqualTo(3);
        assertThat(active.registryId()).isEqualTo("vm-7");
        assertThat(active.providerId()).isEqualTo("vsphere-vm-7");
        assertThat(active.attributes().get("cpu").asInt()).isEqualTo(2);

        handler.handle(feed(3, "job.finished", "job", "job_1", wfrn, finished("job_1", "succeeded", null))).block();
        assertThat(current(wfrn).version()).as("a success after the registration changes nothing").isEqualTo(3);
        handler.handle(registration).block();
        assertThat(current(wfrn).version()).as("the same event twice is applied once").isEqualTo(3);

        List<OutboxEntry> published = outbox.all().collectList().block();
        assertThat(published).extracting(e -> e.event().type()).containsExactly("ResourceRequested", "ProvisioningStarted", "ProvisioningSucceeded");
        assertThat(published).allMatch(e -> e.topic().equals("state.resources") && e.partitionKey().equals(wfrn));
        OutboxEntry last = published.get(2);
        assertThat(last.event().aggregate()).isEqualTo("resource");
        assertThat(last.event().aggregateId()).isEqualTo(wfrn);
        assertThat(last.event().key()).isEqualTo("ProvisioningSucceeded:" + wfrn + ":3");
        assertThat(last.event().context().accountId()).isEqualTo(ACCOUNT);
        assertThat(last.event().data().get("version").asLong()).isEqualTo(3);
        assertThat(last.event().data().get("state").asText()).isEqualTo("active");
        assertThat(last.event().data().get("resourceType").asText()).isEqualTo("vm");
        assertThat(last.event().data().get("registryId").asText()).isEqualTo("vm-7");
    }

    @Test
    void aResourceThePlatformMadeOnItsOwnIsImported() {
        String wfrn = "wfrn:wf:compute:global:" + ACCOUNT + ":vm/vm-legacy";
        com.iaf.console.platform.model.Resource record = record("vm-legacy", wfrn, "job_0", "ready", "legacy");
        when(platform.getResourceByWfrn(wfrn)).thenReturn(Mono.just(record));
        handler.handle(feed(10, "resource.registered", "resource", "vm-legacy", null, registered(record, "job_0"))).block();
        Resource imported = current(wfrn);
        assertThat(imported.state()).isEqualTo(ResourceState.ACTIVE);
        assertThat(imported.version()).isEqualTo(1);
        assertThat(imported.source()).isEqualTo("backfill");
        assertThat(imported.registryId()).isEqualTo("vm-legacy");
        assertThat(imported.name()).isEqualTo("legacy");
        assertThat(outbox.all().collectList().block()).extracting(e -> e.event().type()).containsExactly("Imported");
    }

    @Test
    void aFailedJobFailsTheRequestAndARetiredRecordLeavesATombstone() {
        Resource pending = mint("web-04");
        String wfrn = pending.wfrn();
        handler.handle(feed(1, "job.finished", "job", "job_2", wfrn, finished("job_2", "failed", "provider.quota"))).block();
        Resource failed = current(wfrn);
        assertThat(failed.state()).isEqualTo(ResourceState.FAILED);
        assertThat(failed.lastError().errorClass()).isEqualTo("provider.quota");
        assertThat(failed.lastError().failedStep()).isEqualTo("createVm");

        String legacy = "wfrn:wf:compute:global:" + ACCOUNT + ":vm/vm-old";
        com.iaf.console.platform.model.Resource record = record("vm-old", legacy, "job_0", "ready", "old");
        when(platform.getResourceByWfrn(legacy)).thenReturn(Mono.just(record));
        handler.handle(feed(2, "resource.registered", "resource", "vm-old", null, registered(record, "job_0"))).block();
        handler.handle(feed(3, "resource.retired", "resource", "vm-old", null, Json.object().put("id", "vm-old").put("type", "vm").put("by", "platform")
                .put("wfrn", legacy).put("accountId", ACCOUNT).put("region", REGION).put("jobId", "job_9"))).block();
        Resource gone = current(legacy);
        assertThat(gone.state()).isEqualTo(ResourceState.DELETED);
        assertThat(gone.deletedAt()).isNotNull();
        assertThat(gone.tombstoneUntil()).isEqualTo(gone.deletedAt().plus(Duration.ofDays(90)));
        assertThat(gone.lastJobId()).isEqualTo("job_9");
    }

    @Test
    void theReaperSettlesWhatTheFeedMissed() {
        Resource noJob = mint("orphan");
        Resource registeredMeanwhile = mint("quiet");
        Resource failedMeanwhile = mint("sad");
        Resource fresh = mint("fresh");
        when(platform.getResourceByWfrn(registeredMeanwhile.wfrn())).thenReturn(Mono.just(record("vm-q", registeredMeanwhile.wfrn(), "job_q", "ready", "quiet")));
        when(platform.listJobs(any())).thenAnswer(invocation -> {
            Object filter = invocation.getArgument(0);
            String named = String.valueOf(filter);
            if (named.contains(failedMeanwhile.wfrn())) {
                return Mono.just(new PagedList<>(List.of(new JobSummary("job_s", "create-vm", "Create VM", "2.0.1", JobStatus.FAILED, "dana", "rest",
                        T0, T0.plusSeconds(5), 5000L, null, "single", null, null, "provider.down", "createVm", "svc:bff", null,
                        new JobContext(ACCOUNT, REGION, failedMeanwhile.wfrn(), null, Map.of()))), null));
            }
            return Mono.just(new PagedList<>(List.of(), null));
        });

        assertThat(reaper.tick().block().checked()).as("nothing is overdue yet").isZero();
        clock.advance(Duration.ofMinutes(4));
        Resource later = mint("later");
        clock.advance(Duration.ofMinutes(2));

        PendingReaper.Report report = reaper.tick().block();
        assertThat(report.checked()).isEqualTo(4);
        assertThat(report.settled()).isEqualTo(4);
        assertThat(current(noJob.wfrn()).state()).isEqualTo(ResourceState.FAILED);
        assertThat(current(noJob.wfrn()).lastError().errorClass()).isEqualTo("console.noJob");
        assertThat(current(registeredMeanwhile.wfrn()).state()).isEqualTo(ResourceState.ACTIVE);
        assertThat(current(registeredMeanwhile.wfrn()).registryId()).isEqualTo("vm-q");
        assertThat(current(failedMeanwhile.wfrn()).state()).isEqualTo(ResourceState.FAILED);
        assertThat(current(failedMeanwhile.wfrn()).lastError().errorClass()).isEqualTo("provider.down");
        assertThat(current(fresh.wfrn()).state()).as("overdue, but nothing is known yet; it failed as orphan").isEqualTo(ResourceState.FAILED);
        assertThat(current(later.wfrn()).state()).as("not overdue").isEqualTo(ResourceState.PENDING);
    }

    @Test
    void anUnknownTypeCannotBeMintedAndACollidingImportIsRefused() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> inventory.mint(SCOPE, new Commands.Mint("toaster", REGION, "x", "make-toast",
                Json.object(), by("dana"))).block()).isInstanceOf(com.iaf.console.kit.errors.InvalidInputException.class);
        String wfrn = "wfrn:wf:compute:global:" + ACCOUNT + ":vm/vm-1";
        Commands.Import first = new Commands.Import("vm", REGION, wfrn, "vm-1", "vsphere-1", "one", Fixtures.attributes("one", 1, 1), "backfill", by("import"));
        assertThat(inventory.importResource(SCOPE, first).block().state()).isEqualTo(ResourceState.ACTIVE);
        String other = "wfrn:wf:compute:global:" + ACCOUNT + ":vm/vm-1-again";
        Commands.Import colliding = new Commands.Import("vm", REGION, other, "vm-1", "vsphere-1", "one", Fixtures.attributes("one", 1, 1), "backfill", by("import"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> inventory.importResource(SCOPE, colliding).block())
                .isInstanceOf(com.iaf.console.kit.errors.ConflictException.class).hasMessageStartingWith("registry.collision");
        Instant before = clock.instant();
        assertThat(before).isNotNull();
    }
}
