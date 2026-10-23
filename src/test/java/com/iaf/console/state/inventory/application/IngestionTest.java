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
import com.iaf.console.platform.model.JobDetail;
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
    private final PlatformResources registry = new PlatformResources(platform, new ResourceLoader(events, projection));
    private final JobOutcomes outcomes = new JobOutcomes(inventory, registry, platform);
    private final ChangeRequests changes = new ChangeRequests(inventory, outcomes);
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

    private static JobDetail job(String id, String workflow, JobStatus status, String wfrn, String errorClass) {
        return new JobDetail(id, workflow, null, "1.0.0", status, "dana", "rest", T0, status.terminal() ? T0.plusSeconds(5) : null, null, null, "single",
                null, null, errorClass, errorClass == null ? null : "resize", "svc:bff", null, new JobContext(ACCOUNT, REGION, wfrn, null, Map.of()),
                null, null, null, null, null, null, null, null, null, null, null);
    }

    private Resource imported(String name) {
        String wfrn = "wfrn:wf:compute:global:" + ACCOUNT + ":vm/vm-" + name;
        return inventory.importResource(SCOPE, new Commands.Import("vm", REGION, wfrn, "id-" + name, "vsphere-" + name, name,
                Fixtures.attributes(name, 2, 4), "backfill", by("import"))).block();
    }

    private List<String> trail(String wfrn) {
        return events.findEvents(SCOPE, wfrn, 0, 100).map(e -> e.type().wire()).collectList().block();
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
    void aJobOnAResourceThatWaitsForNoneIsNoEvent() {
        Resource active = imported("idle");
        handler.handle(feed(40, "job.started", "job", "job_u", active.wfrn(), Json.object().put("jobId", "job_u").put("workflow", "update-vm")))
                .block();
        assertThat(current(active.wfrn()).version()).as("the request that names the job, or its end, will say").isEqualTo(active.version());
        assertThat(current(active.wfrn()).state()).isEqualTo(ResourceState.ACTIVE);
    }

    @Test
    void aChangeRecordedAfterItsJobEndedIsSettledAtOnce() {
        // the BFF starts the job first and records the change after; here the job is already over
        Resource resized = imported("resized");
        when(platform.getJob("job_u")).thenReturn(Mono.just(job("job_u", "update-vm", JobStatus.SUCCEEDED, resized.wfrn(), null)));
        when(platform.getResourceByWfrn(resized.wfrn())).thenReturn(Mono.just(record("id-resized", resized.wfrn(), "job_c", "ready", "resized")));
        Resource updated = changes.requestUpdate(SCOPE, resized.wfrn(), new Commands.RequestUpdate("job_u", "update-vm", null,
                Json.object().put("cpu", 4), by("dana"))).block();
        assertThat(updated.state()).isEqualTo(ResourceState.ACTIVE);
        assertThat(updated.lastJobId()).isEqualTo("job_u");
        assertThat(updated.attributes().get("environment").asText()).as("the registry's record after the job").isEqualTo("dev");
        assertThat(trail(resized.wfrn())).containsExactly("Imported", "UpdateRequested", "ProvisioningSucceeded");

        Resource kept = imported("kept");
        when(platform.getJob("job_d")).thenReturn(Mono.just(job("job_d", "delete-vm", JobStatus.FAILED, kept.wfrn(), "provider.down")));
        Resource notDeleted = changes.requestDelete(SCOPE, kept.wfrn(), new Commands.RequestDelete("job_d", "delete-vm", null, "clean up", by("dana")))
                .block();
        assertThat(notDeleted.state()).as("a failed delete leaves the resource as it was").isEqualTo(ResourceState.ACTIVE);
        assertThat(notDeleted.lastError().errorClass()).isEqualTo("provider.down");
        assertThat(trail(kept.wfrn())).containsExactly("Imported", "DeleteRequested", "ProvisioningFailed");

        Resource running = imported("running");
        when(platform.getJob("job_r")).thenReturn(Mono.just(job("job_r", "update-vm", JobStatus.RUNNING, running.wfrn(), null)));
        assertThat(changes.requestUpdate(SCOPE, running.wfrn(), new Commands.RequestUpdate("job_r", "update-vm", null, Json.object().put("cpu", 8),
                by("dana"))).block().state()).as("a running job is waited for").isEqualTo(ResourceState.UPDATING);

        Resource elsewhere = imported("elsewhere");
        when(platform.getJob("job_x")).thenReturn(Mono.just(job("job_x", "update-vm", JobStatus.SUCCEEDED, running.wfrn(), null)));
        assertThat(changes.requestUpdate(SCOPE, elsewhere.wfrn(), new Commands.RequestUpdate("job_x", "update-vm", null, Json.object().put("cpu", 8),
                by("dana"))).block().state()).as("another resource's job is not this one's outcome").isEqualTo(ResourceState.UPDATING);
    }

    @Test
    void theReaperSettlesAnUpdateOrADeleteByItsOwnJob() {
        Resource deletedMeanwhile = imported("gone-by-job");
        inventory.requestDelete(SCOPE, deletedMeanwhile.wfrn(), new Commands.RequestDelete("job_del", "delete-vm", null, "clean up", by("dana"))).block();
        when(platform.getJob("job_del")).thenReturn(Mono.just(job("job_del", "delete-vm", JobStatus.SUCCEEDED, deletedMeanwhile.wfrn(), null)));

        Resource stillRunning = imported("running");
        inventory.requestUpdate(SCOPE, stillRunning.wfrn(), new Commands.RequestUpdate(null, "update-vm", null, Json.object().put("cpu", 4), by("dana")))
                .block();

        Resource neverStarted = imported("never");
        inventory.requestDelete(SCOPE, neverStarted.wfrn(), new Commands.RequestDelete(null, "delete-vm", null, "clean up", by("dana"))).block();
        when(platform.getResourceByWfrn(neverStarted.wfrn())).thenReturn(Mono.just(record("id-never", neverStarted.wfrn(), "job_c", "ready", "never")));

        Resource vanished = imported("vanished");
        inventory.requestDelete(SCOPE, vanished.wfrn(), new Commands.RequestDelete(null, "delete-vm", null, "clean up", by("dana"))).block();

        // an earlier change's job must never be taken for this one's: a delete nothing started stays a resource
        Resource earlier = imported("earlier");
        inventory.registered(SCOPE, earlier.wfrn(), new Commands.Registration("job_old", "update-vm", null, "id-earlier", "vsphere-earlier", "earlier",
                Fixtures.attributes("earlier", 4, 8), by("platform"))).block();
        assertThat(current(earlier.wfrn()).lastJobId()).isEqualTo("job_old");
        Resource deleting = inventory.requestDelete(SCOPE, earlier.wfrn(), new Commands.RequestDelete(null, "delete-vm", null, "clean up", by("dana")))
                .block();
        assertThat(deleting.lastJobId()).as("a new change has no job until one is known").isNull();
        assertThat(deleting.workflow()).isEqualTo("delete-vm");
        when(platform.getJob("job_old")).thenReturn(Mono.just(job("job_old", "update-vm", JobStatus.SUCCEEDED, earlier.wfrn(), null)));
        when(platform.getResourceByWfrn(earlier.wfrn())).thenReturn(Mono.just(record("id-earlier", earlier.wfrn(), "job_c", "ready", "earlier")));

        when(platform.listJobs(any())).thenAnswer(invocation -> {
            Object filter = invocation.getArgument(0);
            String named = String.valueOf(filter);
            if (named.contains(stillRunning.wfrn())) {
                return Mono.just(new PagedList<>(List.of(new JobSummary("job_run", "update-vm", "Update VM", "1.0.0", JobStatus.RUNNING, "dana", "rest",
                        T0, null, null, null, "single", null, null, null, null, "svc:bff", null,
                        new JobContext(ACCOUNT, REGION, stillRunning.wfrn(), null, Map.of()))), null));
            }
            if (named.contains(earlier.wfrn())) {
                return Mono.just(new PagedList<>(List.of(new JobSummary("job_old", "update-vm", "Update VM", "1.0.0", JobStatus.SUCCEEDED, "dana", "rest",
                        T0, T0.plusSeconds(5), 5000L, null, "single", null, null, null, null, "svc:bff", null,
                        new JobContext(ACCOUNT, REGION, earlier.wfrn(), null, Map.of()))), null));
            }
            return Mono.just(new PagedList<>(List.of(), null));
        });

        assertThat(reaper.tick().block().checked()).as("nothing has been busy for too long yet").isZero();
        clock.advance(Duration.ofHours(3));
        PendingReaper.Report report = reaper.tick().block();
        assertThat(report.checked()).isEqualTo(5);
        assertThat(report.waiting()).isEqualTo(1);
        assertThat(report.settled()).isEqualTo(4);
        assertThat(current(deletedMeanwhile.wfrn()).state()).as("its own job succeeded").isEqualTo(ResourceState.DELETED);
        assertThat(current(stillRunning.wfrn()).state()).as("a job still runs on it").isEqualTo(ResourceState.UPDATING);
        assertThat(current(neverStarted.wfrn()).state()).as("nothing carried the delete out and the record is there").isEqualTo(ResourceState.ACTIVE);
        assertThat(current(neverStarted.wfrn()).lastError().errorClass()).isEqualTo("console.noJob");
        assertThat(current(vanished.wfrn()).state()).as("the record is gone").isEqualTo(ResourceState.DELETED);
        assertThat(current(earlier.wfrn()).state()).as("the earlier update's success is not this delete's").isEqualTo(ResourceState.ACTIVE);
        assertThat(current(earlier.wfrn()).lastError().errorClass()).isEqualTo("console.noJob");
    }

    /** A create whose job failed: the resource is failed with that job's error on record. */
    private Resource failedCreate(String name, long seq, String jobId) {
        Resource pending = mint(name);
        handler.handle(feed(seq, "job.finished", "job", jobId, pending.wfrn(), finished(jobId, "failed", "provider.quota"))).block();
        Resource failed = current(pending.wfrn());
        assertThat(failed.state()).isEqualTo(ResourceState.FAILED);
        assertThat(failed.lastError().jobId()).isEqualTo(jobId);
        return failed;
    }

    private FeedEvent started(long seq, String jobId, String wfrn) {
        return feed(seq, "job.started", "job", jobId, wfrn, Json.object().put("jobId", jobId).put("workflow", "create-vm").put("version", "2.0.1"));
    }

    @Test
    void aRetryRecordedBeforeTheRerunIsEndedByTheRerunAloneNeverByTheFailedJob() {
        Resource failed = failedCreate("again", 60, "job_f");
        String wfrn = failed.wfrn();
        Resource retrying = changes.requestRetry(SCOPE, wfrn, new Commands.RequestRetry(null, null, null, by("dana"))).block();
        assertThat(retrying.state()).isEqualTo(ResourceState.PENDING);

        handler.handle(started(61, "job_f", wfrn)).block();
        handler.handle(feed(62, "job.finished", "job", "job_f", wfrn, finished("job_f", "failed", "provider.quota"))).block();
        assertThat(current(wfrn).state()).as("late events of the failed job end nothing").isEqualTo(ResourceState.PENDING);
        assertThat(current(wfrn).version()).isEqualTo(retrying.version());

        handler.handle(started(63, "job_r", wfrn)).block();
        assertThat(current(wfrn).lastJobId()).isEqualTo("job_r");
        com.iaf.console.platform.model.Resource record = record("vm-a", wfrn, "job_r", "ready", "again");
        when(platform.getResourceByWfrn(wfrn)).thenReturn(Mono.just(record));
        handler.handle(feed(64, "resource.registered", "resource", "vm-a", null, registered(record, "job_r"))).block();
        Resource active = current(wfrn);
        assertThat(active.state()).isEqualTo(ResourceState.ACTIVE);
        assertThat(active.lastError()).isNull();
        assertThat(trail(wfrn)).containsExactly("ResourceRequested", "ProvisioningFailed", "RetryRequested", "ProvisioningStarted",
                "ProvisioningSucceeded");
        List<OutboxEntry> published = outbox.all().collectList().block();
        OutboxEntry retry = published.stream().filter(e -> e.event().type().equals("RetryRequested")).findFirst().orElseThrow();
        assertThat(retry.event().data().get("retryOf").asText()).isEqualTo("job_f");
        assertThat(retry.event().data().get("workflow").asText()).isEqualTo("create-vm");
        assertThat(retry.event().data().get("state").asText()).isEqualTo("pending");
    }

    @Test
    void aRetryRecordedAfterItsRerunEndsAsTheRerunDid() {
        // the rerun succeeded and the feed said so before the BFF recorded the retry: nothing to retry
        Resource first = failedCreate("first", 70, "job_f1");
        handler.handle(started(71, "job_r1", first.wfrn())).block();
        assertThat(current(first.wfrn()).version()).as("a job's start on a failed resource is no event").isEqualTo(first.version());
        com.iaf.console.platform.model.Resource record = record("vm-1", first.wfrn(), "job_r1", "ready", "first");
        when(platform.getResourceByWfrn(first.wfrn())).thenReturn(Mono.just(record));
        handler.handle(feed(72, "resource.registered", "resource", "vm-1", null, registered(record, "job_r1"))).block();
        when(platform.getJob("job_r1")).thenReturn(Mono.just(job("job_r1", "create-vm", JobStatus.SUCCEEDED, first.wfrn(), null)));
        Resource done = changes.requestRetry(SCOPE, first.wfrn(), new Commands.RequestRetry("job_r1", null, null, by("dana"))).block();
        assertThat(done.state()).isEqualTo(ResourceState.ACTIVE);
        assertThat(trail(first.wfrn())).containsExactly("ResourceRequested", "ProvisioningFailed", "ProvisioningSucceeded");

        // the rerun failed too, before the retry was recorded: the retry is recorded and settled with the second failure
        Resource second = failedCreate("second", 80, "job_f2");
        handler.handle(feed(81, "job.finished", "job", "job_r2", second.wfrn(), finished("job_r2", "failed", "provider.timeout"))).block();
        assertThat(current(second.wfrn()).lastError().jobId()).as("a failure on a resource that waits for none is not recorded").isEqualTo("job_f2");
        when(platform.getJob("job_r2")).thenReturn(Mono.just(job("job_r2", "create-vm", JobStatus.FAILED, second.wfrn(), "provider.timeout")));
        Resource failedAgain = changes.requestRetry(SCOPE, second.wfrn(), new Commands.RequestRetry("job_r2", null, null, by("dana"))).block();
        assertThat(failedAgain.state()).isEqualTo(ResourceState.FAILED);
        assertThat(failedAgain.lastError().jobId()).isEqualTo("job_r2");
        assertThat(failedAgain.lastError().errorClass()).isEqualTo("provider.timeout");
        assertThat(trail(second.wfrn())).containsExactly("ResourceRequested", "ProvisioningFailed", "RetryRequested", "ProvisioningFailed");

        // the rerun still runs: the retry waits for it, and its start is no second event
        Resource third = failedCreate("third", 90, "job_f3");
        when(platform.getJob("job_r3")).thenReturn(Mono.just(job("job_r3", "create-vm", JobStatus.RUNNING, third.wfrn(), null)));
        Resource waiting = changes.requestRetry(SCOPE, third.wfrn(), new Commands.RequestRetry("job_r3", null, null, by("dana"))).block();
        assertThat(waiting.state()).isEqualTo(ResourceState.PENDING);
        assertThat(waiting.lastJobId()).isEqualTo("job_r3");
        handler.handle(started(91, "job_r3", third.wfrn())).block();
        assertThat(current(third.wfrn()).version()).isEqualTo(waiting.version());
        assertThat(changes.requestRetry(SCOPE, third.wfrn(), new Commands.RequestRetry("job_r3", null, null, by("dana"))).block().version())
                .as("the same retry again changes nothing").isEqualTo(waiting.version());
    }

    @Test
    void theReaperFailsARetryThatNoJobCarriesOut() {
        // the platform refused the rerun: the registry still holds the failed attempt, and the only job is the failed one
        Resource failed = failedCreate("refused", 100, "job_f");
        when(platform.getResourceByWfrn(failed.wfrn())).thenReturn(Mono.just(record("vm-r", failed.wfrn(), "job_f", "failed", "refused")));
        when(platform.listJobs(any())).thenReturn(Mono.just(new PagedList<>(List.of(new JobSummary("job_f", "create-vm", "Create VM", "2.0.1",
                JobStatus.FAILED, "dana", "rest", T0, T0.plusSeconds(5), 5000L, null, "single", null, null, "provider.quota", "createVm", "svc:bff", null,
                new JobContext(ACCOUNT, REGION, failed.wfrn(), null, Map.of()))), null)));
        changes.requestRetry(SCOPE, failed.wfrn(), new Commands.RequestRetry(null, null, null, by("dana"))).block();

        clock.advance(Duration.ofMinutes(6));
        PendingReaper.Report report = reaper.tick().block();
        assertThat(report.checked()).isEqualTo(1);
        Resource noJob = current(failed.wfrn());
        assertThat(noJob.state()).isEqualTo(ResourceState.FAILED);
        assertThat(noJob.lastError().errorClass()).as("the failed job is not the retry's outcome").isEqualTo("console.noJob");
        assertThat(trail(failed.wfrn())).containsExactly("ResourceRequested", "ProvisioningFailed", "RetryRequested", "ProvisioningFailed");
    }

    @Test
    void aDeleteRecordedAfterItsJobDeletedTheResourceAnswersTheTombstone() {
        Resource fast = imported("fast");
        handler.handle(feed(110, "resource.retired", "resource", "id-fast", null, Json.object().put("id", "id-fast").put("type", "vm").put("by", "platform")
                .put("wfrn", fast.wfrn()).put("accountId", ACCOUNT).put("region", REGION))).block();
        assertThat(current(fast.wfrn()).state()).isEqualTo(ResourceState.DELETED);
        when(platform.getJob("job_d")).thenReturn(Mono.just(job("job_d", "delete-vm", JobStatus.SUCCEEDED, fast.wfrn(), null)));
        Resource gone = changes.requestDelete(SCOPE, fast.wfrn(), new Commands.RequestDelete("job_d", "delete-vm", null, "clean up", by("dana"))).block();
        assertThat(gone.state()).isEqualTo(ResourceState.DELETED);
        assertThat(trail(fast.wfrn())).containsExactly("Imported", "Deleted");

        when(platform.getJob("job_x")).thenReturn(Mono.just(job("job_x", "delete-vm", JobStatus.SUCCEEDED, "wfrn:wf:compute:global:" + ACCOUNT
                + ":vm/vm-other", null)));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> changes.requestDelete(SCOPE, fast.wfrn(), new Commands.RequestDelete("job_x",
                "delete-vm", null, null, by("dana"))).block()).as("another resource's job").isInstanceOf(
                        com.iaf.console.state.inventory.domain.TombstoneException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> changes.requestDelete(SCOPE, fast.wfrn(), new Commands.RequestDelete(null, "delete-vm",
                null, null, by("dana"))).block()).as("no job named").isInstanceOf(com.iaf.console.state.inventory.domain.TombstoneException.class);
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
