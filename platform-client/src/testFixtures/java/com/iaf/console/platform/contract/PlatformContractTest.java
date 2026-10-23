package com.iaf.console.platform.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import com.iaf.console.platform.assertion.OnBehalfOfAssertions;
import com.iaf.console.platform.client.Accepted;
import com.iaf.console.platform.client.AuditFilter;
import com.iaf.console.platform.client.Conditional;
import com.iaf.console.platform.client.JobFilter;
import com.iaf.console.platform.client.PagedList;
import com.iaf.console.platform.client.PlatformClient;
import com.iaf.console.platform.client.PlatformException;
import com.iaf.console.platform.client.PlatformOperation;
import com.iaf.console.platform.client.RequestOptions;
import com.iaf.console.platform.client.ResourceFilter;
import com.iaf.console.platform.client.Snapshot;
import com.iaf.console.platform.model.AffectedResource;
import com.iaf.console.platform.model.Approval;
import com.iaf.console.platform.model.AuditRow;
import com.iaf.console.platform.model.BackfillRequest;
import com.iaf.console.platform.model.BackfillResult;
import com.iaf.console.platform.model.BatchAccepted;
import com.iaf.console.platform.model.CatalogEntry;
import com.iaf.console.platform.model.CollisionReport;
import com.iaf.console.platform.model.CommandAccepted;
import com.iaf.console.platform.model.CreateServiceAccount;
import com.iaf.console.platform.model.DecisionRequest;
import com.iaf.console.platform.model.DiscoverRequest;
import com.iaf.console.platform.model.EventPage;
import com.iaf.console.platform.model.EvidenceEntry;
import com.iaf.console.platform.model.EvidenceFilter;
import com.iaf.console.platform.model.EvidenceRecord;
import com.iaf.console.platform.model.ExportPackRequest;
import com.iaf.console.platform.model.FeedEvent;
import com.iaf.console.platform.model.Identity;
import com.iaf.console.platform.model.JobAccepted;
import com.iaf.console.platform.model.JobDetail;
import com.iaf.console.platform.model.JobEvent;
import com.iaf.console.platform.model.JobStatus;
import com.iaf.console.platform.model.JobStep;
import com.iaf.console.platform.model.JobSummary;
import com.iaf.console.platform.model.Lineage;
import com.iaf.console.platform.model.MetricsRequest;
import com.iaf.console.platform.model.MetricsResult;
import com.iaf.console.platform.model.ObserveBatchRequest;
import com.iaf.console.platform.model.ObserveBatchResult;
import com.iaf.console.platform.model.ObserveListPage;
import com.iaf.console.platform.model.ObserveListRequest;
import com.iaf.console.platform.model.ObserveResult;
import com.iaf.console.platform.model.PlatformError;
import com.iaf.console.platform.model.Preview;
import com.iaf.console.platform.model.PreviewStep;
import com.iaf.console.platform.model.Quota;
import com.iaf.console.platform.model.QuotaUpdate;
import com.iaf.console.platform.model.Region;
import com.iaf.console.platform.model.RegionAvailability;
import com.iaf.console.platform.model.RerunRequest;
import com.iaf.console.platform.model.Resource;
import com.iaf.console.platform.model.ResourceType;
import com.iaf.console.platform.model.RetryRequest;
import com.iaf.console.platform.model.RolloutPolicy;
import com.iaf.console.platform.model.Scope;
import com.iaf.console.platform.model.ServiceAccount;
import com.iaf.console.platform.model.ServiceAccountCreated;
import com.iaf.console.platform.model.ServiceAccountRotated;
import com.iaf.console.platform.model.SkipRequest;
import com.iaf.console.platform.model.StartJobRequest;
import com.iaf.console.platform.model.Verification;

import reactor.core.publisher.Mono;

/**
 * The behaviour every platform must show the console, run through the client: CI runs it against the fake and a
 * real platform runs it when one is reachable.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class PlatformContractTest {

    /** The clients and identities one platform under test offers; the optional ones switch their tests off when null. */
    public record Platform(
            String baseUrl,
            PlatformClient admin,
            PlatformClient console,
            String accountId,
            PlatformClient otherConsole,
            String otherAccountId,
            PlatformClient internal,
            OnBehalfOfAssertions.Signer signer,
            OnBehalfOfAssertions.User user,
            Duration jobTimeout) {

        public Platform {
            Objects.requireNonNull(baseUrl, "baseUrl");
            Objects.requireNonNull(admin, "admin");
            Objects.requireNonNull(console, "console");
            Objects.requireNonNull(accountId, "accountId");
            if (signer != null) {
                Objects.requireNonNull(user, "user goes with the signer");
            }
            jobTimeout = jobTimeout == null ? Duration.ofSeconds(60) : jobTimeout;
        }
    }

    private static final Duration CALL = Duration.ofSeconds(30);
    private static final Duration POLL = Duration.ofMillis(50);
    private static final Set<JobStatus> TERMINAL = Set.of(JobStatus.SUCCEEDED, JobStatus.FAILED, JobStatus.CANCELLED, JobStatus.ROLLED_BACK);

    private Platform platform;
    private String region;
    private String imageId;
    private String seedRequestId;
    private String seedJobId;
    private String vmId;
    private String vmProviderId;
    private String vmWfrn;

    /** Connects to the platform under test; called once per class. */
    protected abstract Platform connect();

    /** Releases what {@link #connect()} opened. */
    protected void disconnect(Platform connected) {
    }

    /** Whether the platform lets a test plan a provider failure; the attention tests need it. */
    protected boolean canPlanFailures() {
        return false;
    }

    /** Makes the next call of a provider action fail once, for any object when {@code providerId} is null. */
    protected void planFailure(String provider, String action, String providerId, String errorClass, boolean retryable) {
        throw new UnsupportedOperationException("this platform cannot plan failures");
    }

    @BeforeAll
    void connectToThePlatform() {
        platform = connect();
        List<Region> regions = block(admin().listRegions());
        assertThat(regions).as("regions").isNotEmpty();
        region = regions.stream().filter(r -> Boolean.TRUE.equals(r.isDefault())).findFirst().orElse(regions.get(0)).name();
        imageId = readyImage();
        seedRequestId = "ct-" + nonce();
        Accepted<JobAccepted> accepted = block(console().start(PlatformOperation.CREATE_VM, Map.of(), vmInput("ct-seed-" + nonce(), "medium", "prod"),
                consoleOptions(seedRequestId).withIdempotencyKey("ct-seed-" + nonce())));
        assertThat(accepted.body().jobId()).isNotBlank();
        assertThat(accepted.body().links()).containsKey("job");
        seedJobId = accepted.body().jobId();
        JobDetail job = awaitFinished(console(), seedJobId);
        assertThat(job.status()).as("the seed VM job: %s", job).isEqualTo(JobStatus.SUCCEEDED);
        // the workflow's output names the provider's id; the registry answers it and names the record it minted
        vmProviderId = (String) job.output().get("vmId");
        assertThat(vmProviderId).isNotBlank();
        Resource seed = block(console().getResource(vmProviderId));
        assertThat(seed.providerId()).isEqualTo(vmProviderId);
        vmId = seed.id();
        vmWfrn = seed.wfrn();
        assertThat(vmWfrn).startsWith("wfrn:");
    }

    @AfterAll
    void disconnectFromThePlatform() {
        if (platform != null) {
            disconnect(platform);
        }
    }

    // identity

    @Test
    void theCallerIsIdentified() {
        Identity person = block(admin().me());
        assertThat(person.id()).isNotBlank();
        assertThat(person.kind()).isEqualTo("user");
        Identity service = block(console().me());
        assertThat(service.id()).isNotBlank();
        assertThat(service.kind()).isEqualTo("service");
        assertThat(service.delegation()).isNull();
    }

    @Test
    void assertionsFollowTheDelegationRules() {
        assumeTrue(platform.signer() != null, "no assertion signer for this platform");
        OnBehalfOfAssertions.Signer signer = platform.signer();
        OnBehalfOfAssertions.User user = platform.user();
        Map<String, Object> input = vmInput("ct-assert", "small", "dev");

        PlatformException person = failure(admin().preview(PlatformOperation.CREATE_VM_PREVIEW, Map.of(), input,
                RequestOptions.none().withOnBehalfOf(signer.sign(user, platform.accountId(), region))));
        assertThat(person.isBadRequest()).as("a person may not carry an assertion: %s", person.status()).isTrue();

        PlatformException otherAccount = failure(console().preview(PlatformOperation.CREATE_VM_PREVIEW, Map.of(), input,
                RequestOptions.none().withOnBehalfOf(signer.sign(user, "000000000000", region))));
        assertThat(otherAccount.isBadRequest()).as("an assertion for another account: %s", otherAccount.status()).isTrue();

        PlatformException naked = failure(console().preview(PlatformOperation.CREATE_VM_PREVIEW, Map.of(), input,
                RequestOptions.none().withConsoleRequestId("ct-naked")));
        assertThat(naked.isBadRequest()).as("context headers without a delegation: %s", naked.status()).isTrue();

        PlatformException malformed = failure(console().preview(PlatformOperation.CREATE_VM_PREVIEW, Map.of(), input,
                RequestOptions.none().withOnBehalfOf("not-an-assertion")));
        assertThat(malformed.isUnauthorized()).as("a malformed assertion: %s", malformed.status()).isTrue();

        OnBehalfOfAssertions.Signer stranger = new OnBehalfOfAssertions.Signer(OnBehalfOfAssertions.generateKey("stranger"), signer.issuer(),
                signer.audience());
        PlatformException untrusted = failure(console().preview(PlatformOperation.CREATE_VM_PREVIEW, Map.of(), input,
                RequestOptions.none().withOnBehalfOf(stranger.sign(user, platform.accountId(), region))));
        assertThat(untrusted.isUnauthorized()).as("an untrusted key: %s", untrusted.status()).isTrue();

        String once = signer.sign(user, platform.accountId(), region);
        block(console().preview(PlatformOperation.CREATE_VM_PREVIEW, Map.of(), input, RequestOptions.none().withOnBehalfOf(once)));
        PlatformException replayed = failure(console().preview(PlatformOperation.CREATE_VM_PREVIEW, Map.of(), input,
                RequestOptions.none().withOnBehalfOf(once)));
        assertThat(replayed.isUnauthorized()).as("a replayed assertion: %s", replayed.status()).isTrue();

        Instant past = Instant.now().minusSeconds(300);
        String expired = signer.sign(new OnBehalfOfAssertions.Claims(signer.issuer(), signer.audience(), user.subject(), user.name(), user.email(),
                user.groups(), platform.accountId(), region, UUID.randomUUID().toString(), past, past.plusSeconds(30)));
        PlatformException stale = failure(console().preview(PlatformOperation.CREATE_VM_PREVIEW, Map.of(), input,
                RequestOptions.none().withOnBehalfOf(expired)));
        assertThat(stale.isUnauthorized()).as("an expired assertion: %s", stale.status()).isTrue();

        Instant now = Instant.now();
        String tooLong = signer.sign(new OnBehalfOfAssertions.Claims(signer.issuer(), signer.audience(), user.subject(), user.name(), user.email(),
                user.groups(), platform.accountId(), region, UUID.randomUUID().toString(), now, now.plusSeconds(600)));
        PlatformException oversized = failure(console().preview(PlatformOperation.CREATE_VM_PREVIEW, Map.of(), input,
                RequestOptions.none().withOnBehalfOf(tooLong)));
        assertThat(oversized.isUnauthorized()).as("an assertion living longer than a minute: %s", oversized.status()).isTrue();
    }

    @Test
    void serviceAccountsAreCreatedRotatedAndDeleted() {
        String name = "ct-" + nonce();
        ServiceAccountCreated created = block(admin().createServiceAccount(
                new CreateServiceAccount(name, List.of(), List.of("team-compute"), null, "console", platform.accountId())));
        assertThat(created.id()).isNotBlank();
        assertThat(created.token()).startsWith("iaf_");
        assertThat(created.scopes()).contains(Scope.JOBS_START, Scope.CONSOLE_ACT).doesNotContain(Scope.ADMIN);

        PlatformClient fresh = clientFor(created.token());
        Identity me = block(fresh.me());
        assertThat(me.kind()).isEqualTo("service");
        assertThat(me.teams()).contains("team-compute");
        assertThat(block(admin().listServiceAccounts())).extracting(ServiceAccount::id).contains(created.id());

        ServiceAccountRotated rotated = block(admin().rotateServiceAccount(created.id(), RequestOptions.none()));
        assertThat(rotated.id()).isEqualTo(created.id());
        assertThat(rotated.token()).startsWith("iaf_").isNotEqualTo(created.token());
        assertThat(rotated.graceUntil()).isAfter(Instant.now().minusSeconds(60));
        assertThat(block(clientFor(rotated.token()).me()).id()).isEqualTo(me.id());
        assertThat(block(fresh.me()).id()).as("the old token works until the grace period ends").isEqualTo(me.id());

        block(admin().deleteServiceAccount(created.id(), RequestOptions.none()));
        assertThat(block(admin().listServiceAccounts())).extracting(ServiceAccount::id).doesNotContain(created.id());
        assertThat(failure(clientFor(rotated.token()).me()).isUnauthorized()).isTrue();

        PlatformException tooMuch = failure(admin().createServiceAccount(
                new CreateServiceAccount("ct-admin-" + nonce(), List.of(), List.of("team-compute"), List.of(Scope.ADMIN), "console", platform.accountId())));
        assertThat(tooMuch.isBadRequest()).as("scopes outside the console preset: %s", tooMuch.status()).isTrue();
    }

    // content

    @Test
    void resourceTypesCarryTheConsoleOverlaysAndAnEtag() {
        Conditional<List<ResourceType>> types = block(console().listResourceTypes(null));
        assertThat(types.notModified()).isFalse();
        assertThat(types.etag()).isNotBlank();
        ResourceType vm = types.body().stream().filter(t -> "vm".equals(t.name())).findFirst().orElseThrow();
        assertThat(vm.plural()).isEqualTo("vms");
        assertThat(vm.schema()).isNotNull();
        assertThat(vm.console()).as("the console overlay").isNotNull();
        assertThat(vm.console().list().columns()).isNotEmpty();
        assertThat(vm.observe()).as("the observe block").isNotNull();
        assertThat(vm.observe().provider()).isNotBlank();

        Conditional<List<ResourceType>> again = block(console().listResourceTypes(types.etag()));
        assertThat(again.notModified()).isTrue();
        assertThat(again.etag()).isEqualTo(types.etag());

        Conditional<ResourceType> one = block(console().getResourceType("vm", null));
        assertThat(one.body().name()).isEqualTo("vm");
        assertThat(failure(console().getResourceType("no-such-type", null)).isNotFound()).isTrue();
    }

    @Test
    void theCatalogDescribesEveryRoute() {
        List<CatalogEntry> catalog = block(console().listCatalog(null));
        CatalogEntry create = entry(catalog, "create-vm");
        assertThat(create.method()).isEqualTo("POST");
        assertThat(create.path()).isEqualTo("/api/v1/vms");
        assertThat(create.previewPath()).isEqualTo("/api/v1/vms:preview");
        assertThat(create.batchPath()).isEqualTo("/api/v1/vms:batch");
        assertThat(create.input()).isNotNull();
        assertThat(create.effect()).isNotBlank();
        assertThat(create.hasPreview()).isTrue();
        assertThat(create.batch()).isNotNull();
        CatalogEntry update = entry(catalog, "update-vm");
        assertThat(update.method()).isEqualTo("PATCH");
        assertThat(update.path()).isEqualTo("/api/v1/vms/{vmId}");
        assertThat(update.subjectInput()).isEqualTo("vmId");
        assertThat(update.mutableFields()).contains("cpu", "memoryGb");
        List<CatalogEntry> vms = block(console().listCatalog("vm"));
        assertThat(vms).isNotEmpty().allMatch(e -> "vm".equals(e.noun()));
    }

    @Test
    void regionsAreListedWithTheirAvailability() {
        Region one = block(console().getRegion(region));
        assertThat(one.name()).isEqualTo(region);
        assertThat(one.status()).isNotBlank();
        RegionAvailability availability = block(console().getRegionAvailability(region));
        assertThat(availability.types()).contains("vm");
        assertThat(availability.workflows()).contains("create-vm");
        assertThat(failure(console().getRegion("nowhere")).isNotFound()).isTrue();
    }

    // resources

    @Test
    void aGeneratedRouteRunsAJobThatRegistersAResource() {
        JobDetail job = block(console().getJob(seedJobId));
        assertThat(job.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(job.workflow()).isEqualTo("create-vm");
        assertThat(job.steps()).isNotEmpty().allMatch(s -> "succeeded".equals(s.status()));
        assertThat(job.steps()).extracting(JobStep::id).contains("createVm", "registerVm");
        assertThat(job.context()).isNotNull();
        assertThat(job.context().accountId()).isEqualTo(platform.accountId());
        assertThat(job.context().region()).isEqualTo(region);
        if (platform.signer() != null) {
            assertThat(job.onBehalfOf()).isNotNull();
            assertThat(job.onBehalfOf().id()).isEqualTo(platform.user().subject());
            assertThat(job.requester()).isEqualTo(platform.user().subject());
            assertThat(job.context().consoleRequestId()).isEqualTo(seedRequestId);
            assertThat(job.context().labels()).containsEntry("suite", "contract");
            PagedList<JobSummary> byRequest = block(console().listJobs(JobFilter.all().withConsoleRequestId(seedRequestId)));
            assertThat(byRequest.items()).extracting(JobSummary::id).containsExactly(seedJobId);
            PagedList<JobSummary> byLabel = block(console().listJobs(JobFilter.all().withLabel("suite", "contract")));
            assertThat(byLabel.items()).extracting(JobSummary::id).contains(seedJobId);
        }

        List<JobEvent> events = console().streamJobEvents(seedJobId).collectList().block(CALL);
        assertThat(events).isNotNull();
        assertThat(names(events)).startsWith("job.started").endsWith("job.finished").contains("job.step");
        assertThat(events).extracting(JobEvent::type).containsOnly("job", "step");
        assertThat(events).extracting(JobEvent::jobId).containsOnly(seedJobId);
        assertThat(events).extracting(JobEvent::seq).isSorted().doesNotHaveDuplicates();

        Resource vm = block(console().getResource(vmId));
        assertThat(vm.type()).isEqualTo("vm");
        assertThat(vm.status()).isEqualTo("ready");
        assertThat(vm.accountId()).isEqualTo(platform.accountId());
        assertThat(vm.region()).isEqualTo(region);
        assertThat(vm.wfrn()).contains(platform.accountId()).contains(vmId);
        assertThat(vm.providerId()).isEqualTo(vmProviderId);
        assertThat(vm.createdBy().jobId()).isEqualTo(seedJobId);
        assertThat(vm.builtFrom()).contains(imageId);
        assertThat(vm.attributes()).containsEntry("environment", "prod");

        PagedList<JobSummary> mine = block(console().listJobs(JobFilter.ofAccount(platform.accountId()).withWorkflow("create-vm")));
        assertThat(mine.items()).extracting(JobSummary::id).contains(seedJobId);
        assertThat(mine.items()).allMatch(j -> j.context() != null && platform.accountId().equals(j.context().accountId()));
        PagedList<JobSummary> done = block(console().listJobs(JobFilter.all().withStatus("succeeded").withLimit(5)));
        assertThat(done.items()).hasSizeLessThanOrEqualTo(5).allMatch(j -> j.status() == JobStatus.SUCCEEDED);
    }

    @Test
    void resourcesPageAndResolveByWfrn() {
        PagedList<Resource> first = block(console().listResources(ResourceFilter.ofType("vm").withLimit(1)));
        assertThat(first.items()).hasSize(1);
        assertThat(first.items()).allMatch(r -> platform.accountId().equals(r.accountId()) || "public".equals(r.visibility()));
        if (first.hasNext()) {
            PagedList<Resource> second = block(console().listResources(ResourceFilter.ofType("vm").withLimit(1).withAfter(first.next())));
            assertThat(second.items()).hasSize(1);
            assertThat(second.items().get(0).id()).isNotEqualTo(first.items().get(0).id());
        }
        Resource byWfrn = block(console().getResourceByWfrn(vmWfrn));
        assertThat(byWfrn.id()).isEqualTo(vmId);
        assertThat(block(console().listResources(ResourceFilter.all().withWfrn(vmWfrn))).items()).extracting(Resource::id).containsExactly(vmId);
        Lineage lineage = block(console().getResourceLineage(vmId));
        assertThat(lineage.resource().id()).isEqualTo(vmId);
        assertThat(lineage.builtFrom()).extracting(Resource::id).contains(imageId);
        assertThat(block(console().getOfType("vms", vmId)).id()).isEqualTo(vmId);
        assertThat(block(console().listOfType("vms", ResourceFilter.all().withLimit(5))).items()).isNotEmpty().allMatch(r -> "vm".equals(r.type()));
        assertThat(failure(console().getResource("vm-does-not-exist")).isNotFound()).isTrue();
        assertThat(failure(console().getResourceByWfrn(vmWfrn + "0")).isNotFound()).as("a well-formed name nothing answers to").isTrue();
    }

    @Test
    void observationsReadTheProvidersTruth() {
        ObserveResult observed = block(console().observeResource(vmId));
        assertThat(observed.status()).isEqualTo("found");
        assertThat(observed.provider()).isNotBlank();
        assertThat(observed.observed()).isNotEmpty();
        assertThat(observed.at()).isNotNull();
        ObserveResult byWfrn = block(console().observeResourceByWfrn(vmWfrn));
        assertThat(byWfrn.status()).isEqualTo("found");
        assertThat(byWfrn.observed().keySet()).isEqualTo(observed.observed().keySet());
        ObserveBatchResult many = block(console().observeResources(new ObserveBatchRequest(List.of(vmId), List.of(vmWfrn))));
        assertThat(many.results()).hasSize(2).allMatch(r -> "found".equals(r.status()));
        assertThat(failure(console().observeResource("vm-does-not-exist")).isNotFound()).isTrue();
        ObserveListPage discovered = block(admin().discover("vm", new DiscoverRequest(region, "prod", null, 50)));
        assertThat(discovered.items()).isNotEmpty();
        assertThat(discovered.provider()).isNotBlank();
        assertThat(discovered.items()).allMatch(i -> i.providerId() != null && i.observed() != null);
        try {
            ObserveListPage listed = block(console().observeList("vm", new ObserveListRequest(region, "prod", null, null, 50)));
            assertThat(listed.items()).allMatch(i -> i.providerId() != null);
        } catch (PlatformException e) {
            assertThat(e.isNotFound()).as("observe-list is answered or 404: %s", e.status()).isTrue();
        }
        try {
            MetricsResult metrics = block(console().getResourceMetrics(vmId, new MetricsRequest(null, null, null)));
            assertThat(metrics.series()).isNotNull();
        } catch (PlatformException e) {
            assertThat(e.isNotFound()).as("metrics are answered or 404: %s", e.status()).isTrue();
        }
    }

    @Test
    void backfillsAndCollisionsAreReported() {
        CollisionReport report = block(admin().findResourceCollisions());
        assertThat(report.collisions()).isNotNull();
        BackfillResult backfill = block(admin().backfillWfrns(new BackfillRequest(platform.accountId(), region, "team-compute"), RequestOptions.none()));
        assertThat(backfill.minted()).isGreaterThanOrEqualTo(0);
        assertThat(backfill.items()).hasSize(backfill.minted());
        assertThat(backfill.items()).allMatch(i -> i.wfrn() != null && i.wfrn().contains(platform.accountId()));
    }

    // jobs

    @Test
    void idempotencyKeysReplayAndRefuseMismatches() {
        String key = "ct-" + nonce();
        Map<String, Object> input = vmInput("ct-idem-" + nonce(), "small", "dev");
        Accepted<JobAccepted> first = block(console().start(PlatformOperation.CREATE_VM, Map.of(), input, consoleOptions(null).withIdempotencyKey(key)));
        assertThat(first.idempotentReplay()).isFalse();
        Accepted<JobAccepted> replay = block(console().start(PlatformOperation.CREATE_VM, Map.of(), input, consoleOptions(null).withIdempotencyKey(key)));
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.body().jobId()).isEqualTo(first.body().jobId());
        PlatformException mismatch = failure(console().start(PlatformOperation.CREATE_VM, Map.of(), vmInput("ct-other-" + nonce(), "small", "dev"),
                consoleOptions(null).withIdempotencyKey(key)));
        assertThat(mismatch.status()).isEqualTo(422);
        assertThat(mismatch.isIdempotencyMismatch()).isTrue();
        awaitFinished(console(), first.body().jobId());
    }

    @Test
    void previewsDescribeStepsResourcesAndPolicy() {
        Preview preview = block(console().preview(PlatformOperation.CREATE_VM_PREVIEW, Map.of(), vmInput("ct-preview", "large", "dev"), consoleOptions(null)));
        assertThat(preview.workflow()).isEqualTo("create-vm");
        assertThat(preview.steps()).extracting(PreviewStep::id).contains("createVm", "registerVm");
        assertThat(preview.affectedResources()).extracting(AffectedResource::id).contains(imageId);
        assertThat(preview.policy()).isNotNull();
        assertThat(preview.policy().decision()).isEqualTo("allow");
        if (preview.cost() != null) {
            assertThat(preview.cost().monthly()).isPositive();
            assertThat(preview.cost().currency()).isNotBlank();
        }
        Preview update = block(console().preview(PlatformOperation.UPDATE_VM_PREVIEW, Map.of("vmId", vmId), Map.of("cpu", 8), consoleOptions(null)));
        assertThat(update.workflow()).isEqualTo("update-vm");
        assertThat(update.policy().decision()).as("a production resize needs an approval").isEqualTo("needs-approval");
        assertThat(update.affectedResources()).extracting(AffectedResource::id).contains(vmId);
    }

    @Test
    void theGenericJobRouteAcceptsCommands() {
        Accepted<CommandAccepted> accepted = block(console().startJob(StartJobRequest.of("create-vm", vmInput("ct-generic-" + nonce(), "small", "dev")),
                consoleOptions(null)));
        assertThat(accepted.body().id()).isNotBlank();
        assertThat(accepted.body().links()).containsKey("job");
        assertThat(awaitFinished(console(), accepted.body().id()).status()).isEqualTo(JobStatus.SUCCEEDED);
        PlatformException unknown = failure(console().startJob(StartJobRequest.of("no-such-workflow", Map.of()), consoleOptions(null)));
        assertThat(unknown.isNotFound()).isTrue();
        PlatformException invalid = failure(console().start(PlatformOperation.CREATE_VM, Map.of(), Map.of("name", "ct-no-image"), consoleOptions(null)));
        assertThat(invalid.isBadRequest()).isTrue();
        assertThat(invalid.problems()).as("the schema problems").isNotEmpty();
    }

    @Test
    void aProductionChangeWaitsForAnApprover() {
        Accepted<JobAccepted> accepted = block(console().start(PlatformOperation.UPDATE_VM, Map.of("vmId", vmId),
                Map.of("cpu", 8, "reason", "contract test"), consoleOptions(null)));
        String jobId = accepted.body().jobId();
        JobDetail waiting = awaitStatus(console(), jobId, JobStatus.WAITING_APPROVAL);
        assertThat(waiting.approvals()).hasSize(1);
        Approval approval = waiting.approvals().get(0);
        assertThat(approval.status()).isEqualTo("pending");
        assertThat(approval.group()).isNotBlank();
        assertThat(approval.jobId()).isEqualTo(jobId);
        assertThat(block(admin().listApprovals("pending"))).extracting(Approval::id).contains(approval.id());
        assertThat(block(console().getApproval(approval.id())).jobId()).isEqualTo(jobId);

        PlatformException refused = failure(console().approve(approval.id(), new DecisionRequest("myself"), consoleOptions(null)));
        assertThat(refused.isForbidden() || refused.isNotFound()).as("the console account is not an approver: %s", refused.status()).isTrue();

        Accepted<CommandAccepted> decided = block(admin().approve(approval.id(), new DecisionRequest("looks right"), RequestOptions.none()));
        assertThat(decided.body().id()).isEqualTo(approval.id());
        assertThat(decided.body().status()).isEqualTo("approved");
        JobDetail done = awaitFinished(console(), jobId);
        assertThat(done.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(done.approvals().get(0).status()).isEqualTo("approved");
        assertThat(done.approvals().get(0).decidedBy()).isNotBlank();
        assertThat(block(console().getResource(vmId)).attributes()).containsEntry("cpu", 8);
        List<JobEvent> events = console().streamJobEvents(jobId).collectList().block(CALL);
        assertThat(events).isNotNull();
        assertThat(names(events)).contains("job.approval-requested", "job.approval-decided");
        assertThat(events).extracting(JobEvent::type).contains("approval");

        Accepted<JobAccepted> second = block(console().start(PlatformOperation.UPDATE_VM, Map.of("vmId", vmId), Map.of("cpu", 4), consoleOptions(null)));
        JobDetail waitingAgain = awaitStatus(console(), second.body().jobId(), JobStatus.WAITING_APPROVAL);
        Accepted<CommandAccepted> rejection = block(admin().reject(waitingAgain.approvals().get(0).id(), new DecisionRequest("not now"),
                RequestOptions.none()));
        assertThat(rejection.body().status()).isEqualTo("rejected");
        JobDetail rejected = awaitFinished(console(), second.body().jobId());
        assertThat(rejected.status()).isEqualTo(JobStatus.FAILED);
        assertThat(rejected.errorClass()).isEqualTo("approval.rejected");
        assertThat(block(console().getResource(vmId)).attributes()).containsEntry("cpu", 8);
    }

    @Test
    void cancelStopsAJobWhereItStands() {
        Accepted<JobAccepted> accepted = block(console().start(PlatformOperation.UPDATE_VM, Map.of("vmId", vmId), Map.of("memoryGb", 32),
                consoleOptions(null)));
        String jobId = accepted.body().jobId();
        awaitStatus(console(), jobId, JobStatus.WAITING_APPROVAL);
        Accepted<CommandAccepted> cancelled = block(console().cancelJob(jobId, consoleOptions(null)));
        assertThat(cancelled.body().id()).isEqualTo(jobId);
        JobDetail done = awaitFinished(console(), jobId);
        assertThat(done.status()).isEqualTo(JobStatus.CANCELLED);
        List<JobEvent> events = console().streamJobEvents(jobId).collectList().block(CALL);
        assertThat(events).isNotNull();
        assertThat(names(events)).endsWith("job.cancelled");
        assertThat(failure(console().cancelJob(jobId, consoleOptions(null))).isConflict()).isTrue();
    }

    @Test
    void aFinishedJobIsRerunFromAStep() {
        Accepted<CommandAccepted> rerun = block(console().rerunJob(seedJobId, new RerunRequest("createVm", null), consoleOptions(null)));
        String rerunId = rerun.body().id();
        assertThat(rerunId).isNotBlank().isNotEqualTo(seedJobId);
        JobDetail done = awaitFinished(console(), rerunId);
        assertThat(done.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(done.output().get("vmId")).isNotNull().isNotEqualTo(vmProviderId);
        assertThat(done.steps()).extracting(JobStep::id).contains("useImage", "createVm", "registerVm");
        assertThat(failure(console().rerunJob(seedJobId, new RerunRequest("notAStep", null), consoleOptions(null))).isBadRequest()).isTrue();
        assertThat(failure(console().skipJobStep(seedJobId, new SkipRequest("waitReady"), consoleOptions(null))).isConflict()).isTrue();
        assertThat(failure(console().retryJob(seedJobId, new RetryRequest(null, null), consoleOptions(null))).isConflict()).isTrue();
        assertThat(failure(console().abortJob(seedJobId, consoleOptions(null))).isConflict()).isTrue();
        assertThat(failure(console().getJob("job-does-not-exist")).isNotFound()).isTrue();
    }

    @Test
    void batchesRunTheirRowsInWaves() {
        String n = nonce();
        List<Map<String, Object>> items = List.of(Map.of("name", "ct-row-a-" + n), Map.of("name", "ct-row-b-" + n), Map.of("name", "ct-row-c-" + n));
        Map<String, Object> defaults = Map.of("image", imageId, "size", "small", "environment", "dev");
        Accepted<BatchAccepted> accepted = block(console().batch(PlatformOperation.CREATE_VM_BATCH, items, defaults,
                new RolloutPolicy(List.of(50, 100), 2, 50), null, consoleOptions(null)));
        assertThat(accepted.body().rows()).isEqualTo(3);
        String parentId = accepted.body().jobId();
        JobDetail parent = awaitFinished(console(), parentId);
        assertThat(parent.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(parent.kind()).isEqualTo("batch");
        assertThat(parent.batch()).isNotNull();
        assertThat(parent.batch().rows()).hasSize(3).allMatch(r -> "succeeded".equals(r.status()) && r.jobId() != null);
        assertThat(parent.batch().waves()).hasSize(2);
        assertThat(parent.children()).hasSize(3);
        assertThat(parent.progress()).isNotNull();
        assertThat(parent.progress().done()).isEqualTo(3);
        PagedList<JobSummary> rows = block(console().listJobs(JobFilter.all().withParent(parentId)));
        assertThat(rows.items()).hasSize(3).allMatch(j -> parentId.equals(j.parentId()) && j.row() != null && j.status() == JobStatus.SUCCEEDED);
        List<JobEvent> events = console().streamJobEvents(parentId).collectList().block(CALL);
        assertThat(events).isNotNull();
        assertThat(names(events)).contains("batch.wave").endsWith("job.finished");
        assertThat(events).extracting(JobEvent::type).contains("batch");
        assertThat(failure(console().retryFailedRows(parentId, consoleOptions(null))).isConflict()).isTrue();

        PlatformException bad = failure(console().batch(PlatformOperation.CREATE_VM_BATCH,
                List.of(Map.of("name", "ct-ok-" + n), Map.of("name", "NOT A VALID NAME")), defaults, null, null, consoleOptions(null)));
        assertThat(bad.isBadRequest()).isTrue();
        assertThat(bad.error().rows()).extracting(PlatformError.RowProblems::index).containsExactly(1);
    }

    @Test
    void attentionIsResolvedByRetrySkipOrAbort() {
        assumeTrue(canPlanFailures(), "this platform cannot plan failures");
        for (String action : List.of("retry", "skip", "abort")) {
            planFailure("vsphere", "vm.waitForPowerState", null, "timeout", true);
            Accepted<JobAccepted> accepted = block(console().start(PlatformOperation.CREATE_VM, Map.of(),
                    vmInput("ct-" + action + "-" + nonce(), "small", "dev"), consoleOptions(null)));
            String jobId = accepted.body().jobId();
            JobDetail paused = awaitStatus(console(), jobId, JobStatus.NEEDS_ATTENTION);
            assertThat(paused.attention()).isNotNull();
            assertThat(paused.attention().step()).isEqualTo("waitReady");
            assertThat(paused.attention().error().errorClass()).isEqualTo("timeout");
            assertThat(paused.attention().options()).contains("retry", "skip", "abort");
            assertThat(paused.failedStep()).isNull();
            switch (action) {
                case "retry" -> block(console().retryJob(jobId, new RetryRequest(null, null), consoleOptions(null)));
                case "skip" -> block(console().skipJobStep(jobId, new SkipRequest("waitReady"), consoleOptions(null)));
                default -> block(console().abortJob(jobId, consoleOptions(null)));
            }
            JobDetail done = awaitFinished(console(), jobId);
            switch (action) {
                case "retry" -> {
                    assertThat(done.status()).isEqualTo(JobStatus.SUCCEEDED);
                    assertThat(step(done, "waitReady").status()).isEqualTo("succeeded");
                    assertThat(step(done, "waitReady").attempt()).isEqualTo(2);
                }
                case "skip" -> {
                    assertThat(done.status()).isEqualTo(JobStatus.SUCCEEDED);
                    assertThat(step(done, "waitReady").status()).isEqualTo("skipped");
                    assertThat(done.output().get("vmId")).isNotNull();
                }
                default -> {
                    assertThat(done.status()).as("the half-made VM is compensated").isEqualTo(JobStatus.ROLLED_BACK);
                    assertThat(done.failedStep()).isEqualTo("waitReady");
                    assertThat(step(done, "createVm").status()).isEqualTo("compensated");
                }
            }
            List<JobEvent> events = console().streamJobEvents(jobId).collectList().block(CALL);
            assertThat(events).isNotNull();
            assertThat(names(events)).contains("job.attention", "job.attention-resolved");
        }
    }

    // the feed

    @Test
    void eventsAreListedStreamedAndSnapshotted() throws Exception {
        PlatformClient reader = feedReader();
        EventPage page = block(reader.listEvents(0L, 100, List.of("job.")));
        assertThat(page.items()).isNotEmpty().allMatch(e -> e.type().startsWith("job."));
        assertThat(page.items()).extracting(FeedEvent::seq).isSorted().doesNotHaveDuplicates();
        assertThat(page.items()).allMatch(e -> e.key() != null && e.id() != null && e.at() != null && e.aggregateId() != null);
        long lastSeq = page.items().get(page.items().size() - 1).seq();
        assertThat(page.next()).isGreaterThanOrEqualTo(lastSeq);
        EventPage mine = block(reader.listEvents(0L, 500, List.of("job.started")));
        assertThat(mine.items()).extracting(FeedEvent::aggregateId).contains(seedJobId);
        FeedEvent seedStarted = mine.items().stream().filter(e -> seedJobId.equals(e.aggregateId())).findFirst().orElseThrow();
        assertThat(seedStarted.context()).isNotNull();
        assertThat(seedStarted.context().accountId()).isEqualTo(platform.accountId());
        if (platform.signer() != null) {
            assertThat(seedStarted.onBehalfOf()).isNotNull();
            assertThat(seedStarted.onBehalfOf().id()).isEqualTo(platform.user().subject());
        }

        FeedEvent replayed = reader.streamEvents(lastSeq - 1, null, List.of("job.")).next().block(CALL);
        assertThat(replayed).isNotNull();
        assertThat(replayed.seq()).isEqualTo(lastSeq);

        long high = block(reader.snapshotResources(null, null, 1)).highWaterSeq();
        CompletableFuture<FeedEvent> live = reader.streamEvents(high, null, List.of("job.started")).next().toFuture();
        Accepted<JobAccepted> accepted = block(console().start(PlatformOperation.CREATE_VM, Map.of(), vmInput("ct-live-" + nonce(), "small", "dev"),
                consoleOptions(null)));
        FeedEvent started = await(live);
        assertThat(started.type()).isEqualTo("job.started");
        assertThat(started.aggregateId()).isEqualTo(accepted.body().jobId());
        assertThat(started.seq()).isGreaterThan(high);
        assertThat(started.context().accountId()).isEqualTo(platform.accountId());
        FeedEvent resumed = reader.streamEvents(null, high, List.of("job.started")).next().block(CALL);
        assertThat(resumed).isNotNull();
        assertThat(resumed.seq()).as("Last-Event-ID resumes where the page ended").isEqualTo(started.seq());
        awaitFinished(console(), accepted.body().jobId());

        Snapshot<Resource> resources = block(reader.snapshotResources(null, null, 2));
        assertThat(resources.items()).hasSizeLessThanOrEqualTo(2);
        assertThat(resources.highWaterSeq()).isGreaterThanOrEqualTo(started.seq());
        if (resources.hasNext()) {
            Snapshot<Resource> more = block(reader.snapshotResources(null, resources.next(), 2));
            assertThat(more.items()).isNotEmpty();
            assertThat(more.items().get(0).id()).isNotEqualTo(resources.items().get(0).id());
        }
        Snapshot<JobSummary> jobs = block(reader.snapshotJobs(null, null, 500));
        assertThat(jobs.items()).extracting(JobSummary::id).contains(seedJobId);

        // an account's console preset acts but never reads the feed
        assertThat(failure(console().listEvents(0L, 10, List.of())).isForbidden()).isTrue();
        assertThat(failure(console().snapshotJobs(null, null, 10)).isForbidden()).isTrue();
    }

    @Test
    void theGlobalJobStreamCarriesEveryJobEvent() throws Exception {
        PlatformClient reader = feedReader();
        long high = block(reader.snapshotJobs(null, null, 1)).highWaterSeq();
        CompletableFuture<List<FeedEvent>> firstTwo = reader.streamJobs(high, null).filter(e -> e.type().startsWith("job.")).take(2).collectList()
                .toFuture();
        Accepted<JobAccepted> accepted = block(console().start(PlatformOperation.CREATE_VM, Map.of(), vmInput("ct-global-" + nonce(), "small", "dev"),
                consoleOptions(null)));
        List<FeedEvent> seen = await(firstTwo);
        assertThat(seen).extracting(FeedEvent::type).startsWith("job.started");
        assertThat(seen).extracting(FeedEvent::aggregateId).containsOnly(accepted.body().jobId());
        awaitFinished(console(), accepted.body().jobId());
    }

    // quotas and evidence

    @Test
    void quotasAreReadAndOverridden() {
        String key = "account:" + platform.accountId();
        Quota quota = block(console().getQuota(key));
        assertThat(quota.key()).isEqualTo(key);
        assertThat(quota.limitPerMinute()).isPositive();
        assertThat(quota.source()).isNotBlank();
        Quota changed = block(admin().putQuota(key, new QuotaUpdate(quota.limitPerMinute() + 10, quota.burst() + 5), RequestOptions.none()));
        assertThat(changed.limitPerMinute()).isEqualTo(quota.limitPerMinute() + 10);
        assertThat(changed.burst()).isEqualTo(quota.burst() + 5);
        assertThat(changed.source()).isEqualTo("override");
        assertThat(block(console().getQuota(key)).limitPerMinute()).isEqualTo(quota.limitPerMinute() + 10);
        PlatformException foreign = failure(console().getQuota("account:000000000000"));
        assertThat(foreign.isForbidden() || foreign.isNotFound()).as("another account's quota: %s", foreign.status()).isTrue();
        PlatformException invalid = failure(admin().putQuota(key, new QuotaUpdate(-1, 0), RequestOptions.none()));
        assertThat(invalid.isBadRequest()).isTrue();
    }

    @Test
    void evidenceRecordsAreSealedVerifiedAndExported() {
        EvidenceRecord record = block(console().getEvidenceRecord(seedJobId));
        assertThat(record.jobId()).isEqualTo(seedJobId);
        assertThat(record.workflow()).isEqualTo("create-vm");
        assertThat(record.entries()).isNotEmpty();
        assertThat(record.entries()).extracting(EvidenceEntry::kind).contains("requested", "step", "closed");
        assertThat(record.entries()).extracting(EvidenceEntry::hash).doesNotContainNull().doesNotHaveDuplicates();
        assertThat(record.latestHash()).isEqualTo(record.entries().get(record.entries().size() - 1).hash());
        assertThat(record.sealed()).isNotNull();
        assertThat(record.sealed().signature()).isNotBlank();
        assertThat(record.retention()).isNotNull();

        Verification verification = block(console().verifyEvidenceRecord(seedJobId));
        assertThat(verification.valid()).isTrue();
        assertThat(verification.entries()).isEqualTo(record.entries().size());
        assertThat(verification.brokenAt()).isNull();

        byte[] json = block(console().exportEvidenceRecord(seedJobId, "json"));
        assertThat(new String(json, StandardCharsets.UTF_8)).contains(seedJobId).contains(record.latestHash());
        byte[] pdf = block(console().exportEvidenceRecord(seedJobId, "pdf"));
        assertThat(new String(pdf, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");

        List<AuditRow> rows = block(console().listAudit(AuditFilter.ofAccount(platform.accountId()).withWorkflow("create-vm")));
        assertThat(rows).extracting(AuditRow::jobId).contains(seedJobId);
        assertThat(rows).allMatch(r -> platform.accountId().equals(r.accountId()) && "create-vm".equals(r.workflow()));

        ExportPackRequest packRequest = new ExportPackRequest(new EvidenceFilter("create-vm", null, null, null, platform.accountId(), null));
        byte[] pack = block(admin().exportEvidencePack(packRequest));
        assertThat(pack.length).isGreaterThan(4);
        assertThat(new String(pack, 0, 2, StandardCharsets.US_ASCII)).as("a zip archive").isEqualTo("PK");
        assertThat(failure(console().exportEvidencePack(packRequest)).isForbidden()).as("the pack is for admins").isTrue();
        assertThat(failure(console().getEvidenceRecord("job-does-not-exist")).isNotFound()).isTrue();
    }

    // isolation

    @Test
    void consoleAccountsNeverSeeEachOther() {
        assumeTrue(platform.otherConsole() != null, "no second console account for this platform");
        PlatformClient other = platform.otherConsole();
        assertThat(failure(other.getJob(seedJobId)).isNotFound()).isTrue();
        assertThat(failure(other.getResource(vmId)).isNotFound()).isTrue();
        assertThat(failure(other.getResourceByWfrn(vmWfrn)).isNotFound()).isTrue();
        assertThat(failure(other.getResourceLineage(vmId)).isNotFound()).isTrue();
        assertThat(failure(other.observeResource(vmId)).isNotFound()).isTrue();
        assertThat(failure(other.getEvidenceRecord(seedJobId)).isNotFound()).isTrue();
        assertThat(failure(other.cancelJob(seedJobId, RequestOptions.none())).isNotFound()).isTrue();
        assertThat(failure(other.rerunJob(seedJobId, new RerunRequest("createVm", null), RequestOptions.none())).isNotFound()).isTrue();
        assertThat(block(other.listJobs(JobFilter.all())).items()).extracting(JobSummary::id).doesNotContain(seedJobId);
        assertThat(block(other.listResources(ResourceFilter.ofType("vm"))).items()).extracting(Resource::id).doesNotContain(vmId);
        assertThat(block(other.listAudit(AuditFilter.all()))).extracting(AuditRow::jobId).doesNotContain(seedJobId);
        assertThat(block(other.listApprovals(null))).extracting(Approval::jobId).doesNotContain(seedJobId);
        try {
            PagedList<JobSummary> asked = block(other.listJobs(JobFilter.ofAccount(platform.accountId())));
            assertThat(asked.items()).extracting(JobSummary::id).doesNotContain(seedJobId);
        } catch (PlatformException e) {
            assertThat(e.isBadRequest() || e.isNotFound()).as("asking for another account: %s", e.status()).isTrue();
        }
        Identity me = block(other.me());
        assertThat(me.kind()).isEqualTo("service");
    }

    // helpers for subclasses

    /** The platform under test. */
    protected Platform platform() {
        return platform;
    }

    /** The region the seed resources live in. */
    protected String region() {
        return region;
    }

    /** A client with the given bearer token against the same platform. */
    protected PlatformClient clientFor(String token) {
        return PlatformClient.builder().baseUrl(platform.baseUrl()).bearerToken(token).build();
    }

    private PlatformClient admin() {
        return platform.admin();
    }

    /** Who reads the feed: the console-internal preset when the platform offers one, else the person. */
    private PlatformClient feedReader() {
        return platform.internal() != null ? platform.internal() : platform.admin();
    }

    /** The domain event names of a job's stream, carried in {@code data.event}. */
    private static List<String> names(List<JobEvent> events) {
        return events.stream().map(e -> e.data() == null ? null : (String) e.data().get("event")).toList();
    }

    private PlatformClient console() {
        return platform.console();
    }

    /** Options for a console call: a fresh assertion when the platform trusts our signer, with the context headers when asked for. */
    private RequestOptions consoleOptions(String consoleRequestId) {
        RequestOptions options = RequestOptions.none();
        if (platform.signer() == null) {
            return options;
        }
        options = options.withOnBehalfOf(platform.signer().sign(platform.user(), platform.accountId(), region));
        if (consoleRequestId != null) {
            options = options.withConsoleRequestId(consoleRequestId).withLabel("suite", "contract");
        }
        return options;
    }

    private Map<String, Object> vmInput(String name, String size, String environment) {
        return Map.of("name", name, "image", imageId, "size", size, "environment", environment);
    }

    private String readyImage() {
        PagedList<Resource> images = block(admin().listResources(ResourceFilter.ofType("image").withStatus("ready")));
        if (!images.items().isEmpty()) {
            return images.items().get(0).id();
        }
        Accepted<JobAccepted> build = block(admin().start(PlatformOperation.BUILD_IMAGE, Map.of(), Map.of("os", "ubuntu-24.04"), RequestOptions.none()));
        JobDetail job = awaitFinished(admin(), build.body().jobId());
        assertThat(job.status()).as("the image build: %s", job).isEqualTo(JobStatus.SUCCEEDED);
        return (String) job.output().get("imageId");
    }

    private static CatalogEntry entry(List<CatalogEntry> catalog, String workflow) {
        return catalog.stream().filter(e -> workflow.equals(e.workflow())).findFirst()
                .orElseThrow(() -> new AssertionError("the catalog has no " + workflow));
    }

    private static JobStep step(JobDetail job, String id) {
        return job.steps().stream().filter(s -> id.equals(s.id())).findFirst().orElseThrow(() -> new AssertionError(job + " has no step " + id));
    }

    private JobDetail await(PlatformClient client, String jobId, Predicate<JobDetail> until) {
        Instant deadline = Instant.now().plus(platform.jobTimeout());
        JobDetail job = block(client.getJob(jobId));
        while (!until.test(job)) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("job " + jobId + " is still " + job.status() + " after " + platform.jobTimeout() + ": " + job);
            }
            sleep();
            job = block(client.getJob(jobId));
        }
        return job;
    }

    private JobDetail awaitFinished(PlatformClient client, String jobId) {
        return await(client, jobId, j -> TERMINAL.contains(j.status()));
    }

    private JobDetail awaitStatus(PlatformClient client, String jobId, JobStatus wanted) {
        JobDetail job = await(client, jobId, j -> j.status() == wanted || TERMINAL.contains(j.status()));
        assertThat(job.status()).as("job %s: %s", jobId, job).isEqualTo(wanted);
        return job;
    }

    private static <T> T await(CompletableFuture<T> future) throws InterruptedException, ExecutionException, TimeoutException {
        return future.get(CALL.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static <T> T block(Mono<T> call) {
        return call.block(CALL);
    }

    private static PlatformException failure(Mono<?> call) {
        try {
            call.block(CALL);
        } catch (PlatformException e) {
            return e;
        }
        throw new AssertionError("the call succeeded; a PlatformException was expected");
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for a job", e);
        }
    }

    private static String nonce() {
        return Long.toString(ThreadLocalRandom.current().nextLong(1L << 40), 36);
    }
}
