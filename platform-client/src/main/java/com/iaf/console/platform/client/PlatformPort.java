package com.iaf.console.platform.client;

import java.util.List;
import java.util.Map;

import com.iaf.console.platform.model.Approval;
import com.iaf.console.platform.model.AuditRow;
import com.iaf.console.platform.model.BackfillRequest;
import com.iaf.console.platform.model.BackfillResult;
import com.iaf.console.platform.model.BatchAccepted;
import com.iaf.console.platform.model.BatchContext;
import com.iaf.console.platform.model.CatalogEntry;
import com.iaf.console.platform.model.CollisionReport;
import com.iaf.console.platform.model.CommandAccepted;
import com.iaf.console.platform.model.CreateServiceAccount;
import com.iaf.console.platform.model.DecisionRequest;
import com.iaf.console.platform.model.DiscoverRequest;
import com.iaf.console.platform.model.EventPage;
import com.iaf.console.platform.model.EvidenceRecord;
import com.iaf.console.platform.model.ExportPackRequest;
import com.iaf.console.platform.model.FeedEvent;
import com.iaf.console.platform.model.Identity;
import com.iaf.console.platform.model.JobAccepted;
import com.iaf.console.platform.model.JobDetail;
import com.iaf.console.platform.model.JobEvent;
import com.iaf.console.platform.model.JobSummary;
import com.iaf.console.platform.model.Lineage;
import com.iaf.console.platform.model.MetricsRequest;
import com.iaf.console.platform.model.MetricsResult;
import com.iaf.console.platform.model.ObserveBatchRequest;
import com.iaf.console.platform.model.ObserveBatchResult;
import com.iaf.console.platform.model.ObserveListPage;
import com.iaf.console.platform.model.ObserveListRequest;
import com.iaf.console.platform.model.ObserveResult;
import com.iaf.console.platform.model.Preview;
import com.iaf.console.platform.model.Quota;
import com.iaf.console.platform.model.QuotaUpdate;
import com.iaf.console.platform.model.Region;
import com.iaf.console.platform.model.RegionAvailability;
import com.iaf.console.platform.model.RerunRequest;
import com.iaf.console.platform.model.Resource;
import com.iaf.console.platform.model.ResourceType;
import com.iaf.console.platform.model.RetryRequest;
import com.iaf.console.platform.model.RolloutPolicy;
import com.iaf.console.platform.model.ServiceAccount;
import com.iaf.console.platform.model.ServiceAccountCreated;
import com.iaf.console.platform.model.ServiceAccountRotated;
import com.iaf.console.platform.model.SkipRequest;
import com.iaf.console.platform.model.StartBatchRequest;
import com.iaf.console.platform.model.StartJobRequest;
import com.iaf.console.platform.model.Verification;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The platform as a console service sees it: one method per operation of the target contract. Every
 * answer is typed; a non-2xx answer is a {@link PlatformException}. Reads of the registry and the
 * jobs are scoped by the platform to the caller (P-18), so the account is in the credentials, not
 * in these signatures.
 */
public interface PlatformPort {

    // identity (P-02, P-03)

    Mono<Identity> me();

    Mono<List<ServiceAccount>> listServiceAccounts();

    Mono<ServiceAccountCreated> createServiceAccount(CreateServiceAccount request);

    Mono<ServiceAccountRotated> rotateServiceAccount(String id, RequestOptions options);

    Mono<Void> deleteServiceAccount(String id, RequestOptions options);

    // resource types (P-06, P-19)

    /** The published types; with the ETag of the copy held, {@code notModified} when it is current. */
    Mono<Conditional<List<ResourceType>>> listResourceTypes(String etag);

    Mono<Conditional<ResourceType>> getResourceType(String name, String etag);

    /** One page of a type's paged read (P-09). */
    Mono<ObserveListPage> observeList(String typeName, ObserveListRequest request);

    /** One page of what the provider has, managed or not (P-09). */
    Mono<ObserveListPage> discover(String typeName, DiscoverRequest request);

    // catalog (P-08) and regions (P-07)

    Mono<List<CatalogEntry>> listCatalog(String noun);

    Mono<List<Region>> listRegions();

    Mono<Region> getRegion(String name);

    Mono<RegionAvailability> getRegionAvailability(String name);

    // resources (P-05, P-09, P-18)

    Mono<PagedList<Resource>> listResources(ResourceFilter filter);

    Mono<Resource> getResourceByWfrn(String wfrn);

    Mono<ObserveResult> observeResourceByWfrn(String wfrn);

    Mono<Resource> getResource(String id);

    Mono<Lineage> getResourceLineage(String id);

    Mono<ObserveResult> observeResource(String id);

    Mono<MetricsResult> getResourceMetrics(String id, MetricsRequest request);

    Mono<ObserveBatchResult> observeResources(ObserveBatchRequest request);

    Mono<BackfillResult> backfillWfrns(BackfillRequest request, RequestOptions options);

    Mono<CollisionReport> findResourceCollisions();

    // jobs (P-02, P-04, P-20, P-21)

    Mono<PagedList<JobSummary>> listJobs(JobFilter filter);

    Mono<Accepted<CommandAccepted>> startJob(StartJobRequest request, RequestOptions options);

    Mono<Accepted<CommandAccepted>> startBatch(StartBatchRequest request, RequestOptions options);

    /** The global job stream from a sequence; {@code lastEventId} resumes like a browser would. */
    Flux<FeedEvent> streamJobs(Long after, Long lastEventId);

    Mono<JobDetail> getJob(String id);

    /** A job's events, history first, then live until it ends. */
    Flux<JobEvent> streamJobEvents(String id);

    Mono<Accepted<CommandAccepted>> cancelJob(String id, RequestOptions options);

    Mono<Accepted<CommandAccepted>> retryJob(String id, RetryRequest request, RequestOptions options);

    Mono<Accepted<CommandAccepted>> skipJobStep(String id, SkipRequest request, RequestOptions options);

    Mono<Accepted<CommandAccepted>> abortJob(String id, RequestOptions options);

    Mono<Accepted<CommandAccepted>> rerunJob(String id, RerunRequest request, RequestOptions options);

    Mono<Accepted<CommandAccepted>> retryFailedRows(String id, RequestOptions options);

    // approvals

    Mono<List<Approval>> listApprovals(String status);

    Mono<Approval> getApproval(String id);

    Mono<Accepted<CommandAccepted>> approve(String id, DecisionRequest request, RequestOptions options);

    Mono<Accepted<CommandAccepted>> reject(String id, DecisionRequest request, RequestOptions options);

    // events (P-01)

    Mono<EventPage> listEvents(Long after, Integer limit, List<String> types);

    Flux<FeedEvent> streamEvents(Long after, Long lastEventId, List<String> types);

    /** The feed from a sequence on, reconnecting with the last sequence seen until the subscriber cancels. */
    Flux<FeedEvent> followEvents(long fromSeq, List<String> types);

    Mono<Snapshot<Resource>> snapshotResources(Long updatedAfter, String cursor, Integer limit);

    Mono<Snapshot<JobSummary>> snapshotJobs(Long updatedAfter, String cursor, Integer limit);

    // quotas (P-10)

    Mono<Quota> getQuota(String key);

    Mono<Quota> putQuota(String key, QuotaUpdate update, RequestOptions options);

    // audit (P-12)

    Mono<List<AuditRow>> listAudit(AuditFilter filter);

    Mono<EvidenceRecord> getEvidenceRecord(String jobId);

    Mono<Verification> verifyEvidenceRecord(String jobId);

    /** The record as a file ({@code pdf} by default). */
    Mono<byte[]> exportEvidenceRecord(String jobId, String format);

    /** A zip of the records a filter selects. */
    Mono<byte[]> exportEvidencePack(ExportPackRequest request);

    // generated routes: the catalog's buttons

    /** Starts a workflow through its generated route; {@code pathParams} holds the subject id when the path has one. */
    Mono<Accepted<JobAccepted>> start(PlatformOperation route, Map<String, String> pathParams, Map<String, Object> input, RequestOptions options);

    /** Starts a workflow at the method and path of a catalog entry. */
    Mono<Accepted<JobAccepted>> start(CatalogEntry entry, Map<String, String> pathParams, Map<String, Object> input, RequestOptions options);

    Mono<Preview> preview(PlatformOperation route, Map<String, String> pathParams, Map<String, Object> input, RequestOptions options);

    Mono<Preview> preview(CatalogEntry entry, Map<String, String> pathParams, Map<String, Object> input, RequestOptions options);

    Mono<Accepted<BatchAccepted>> batch(PlatformOperation route, List<Map<String, Object>> items, Map<String, Object> defaults, RolloutPolicy rollout,
            List<BatchContext> contexts, RequestOptions options);

    Mono<Accepted<BatchAccepted>> batch(CatalogEntry entry, List<Map<String, Object>> items, Map<String, Object> defaults, RolloutPolicy rollout,
            List<BatchContext> contexts, RequestOptions options);

    /** {@code GET /api/v1/<plural>}, the generated read of a type. */
    Mono<PagedList<Resource>> listOfType(String plural, ResourceFilter filter);

    /** {@code GET /api/v1/<plural>/{id}}. */
    Mono<Resource> getOfType(String plural, String id);
}
