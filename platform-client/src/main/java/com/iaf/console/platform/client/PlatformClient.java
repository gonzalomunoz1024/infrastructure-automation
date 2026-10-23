package com.iaf.console.platform.client;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.iaf.console.platform.model.PlatformError;
import com.iaf.console.platform.model.Preview;
import com.iaf.console.platform.model.PriceList;
import com.iaf.console.platform.model.Quota;
import com.iaf.console.platform.model.QuotaUpdate;
import com.iaf.console.platform.model.Region;
import com.iaf.console.platform.model.RegionAvailability;
import com.iaf.console.platform.model.RerunRequest;
import com.iaf.console.platform.model.RehomeRequest;
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
import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;

/**
 * The reactive client of the platform's target API, hand-written over WebClient and typed by the
 * contract test. Built with a base URL and either a service account token or the dev user header.
 * Request bodies and the optional headers come from the caller; paging, ETags, idempotent replays
 * and SSE resumption are handled here.
 */
public final class PlatformClient implements PlatformPort {

    private static final ParameterizedTypeReference<List<ServiceAccount>> SERVICE_ACCOUNTS = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<List<ResourceType>> RESOURCE_TYPES = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<List<PriceList>> PRICE_LISTS = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<List<CatalogEntry>> CATALOG = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<List<Region>> REGIONS = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<List<Resource>> RESOURCES = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<List<JobSummary>> JOBS = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<List<Approval>> APPROVALS = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<List<AuditRow>> AUDIT_ROWS = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<Snapshot<Resource>> RESOURCE_SNAPSHOT = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<Snapshot<JobSummary>> JOB_SNAPSHOT = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ServerSentEvent<FeedEvent>> FEED_SSE = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ServerSentEvent<JobEvent>> JOB_SSE = new ParameterizedTypeReference<>() {
    };

    private final WebClient web;
    private final WebClient streams;
    private final ObjectMapper json;

    private PlatformClient(Builder b) {
        this.json = b.json;
        WebClient.Builder base = b.webClientBuilder != null ? b.webClientBuilder.clone() : WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create().responseTimeout(b.responseTimeout)));
        base.baseUrl(b.baseUrl)
                .codecs(codecs -> {
                    codecs.defaultCodecs().jackson2JsonDecoder(new Jackson2JsonDecoder(json));
                    codecs.defaultCodecs().jackson2JsonEncoder(new Jackson2JsonEncoder(json));
                    codecs.defaultCodecs().maxInMemorySize(b.maxInMemoryBytes);
                })
                .defaultHeaders(h -> {
                    h.set(HttpHeaders.USER_AGENT, b.userAgent);
                    if (b.bearerToken != null) {
                        h.setBearerAuth(b.bearerToken);
                    } else if (b.devUser != null) {
                        h.set(PlatformHeaders.DEV_USER, b.devUser);
                    }
                });
        this.web = base.build();
        WebClient.Builder streaming = b.webClientBuilder != null ? base.clone() : base.clone()
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create()));
        this.streams = streaming.build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** What a client is built from. */
    public static final class Builder {
        private String baseUrl;
        private String bearerToken;
        private String devUser;
        private WebClient.Builder webClientBuilder;
        private ObjectMapper json = PlatformJson.mapper();
        private Duration responseTimeout = Duration.ofSeconds(30);
        private int maxInMemoryBytes = 16 * 1024 * 1024;
        private String userAgent = "console-platform-client";

        private Builder() {
        }

        /** The platform's URL, like {@code http://localhost:18090}. */
        public Builder baseUrl(String url) {
            this.baseUrl = url;
            return this;
        }

        /** A service account token ({@code iaf_...}), sent as a bearer. */
        public Builder bearerToken(String token) {
            this.bearerToken = token;
            return this;
        }

        /** The dev identity header for local runs; ignored when a token is set. */
        public Builder devUser(String user) {
            this.devUser = user;
            return this;
        }

        /** A pre-configured WebClient builder (tests pass one with an exchange function). */
        public Builder webClientBuilder(WebClient.Builder builder) {
            this.webClientBuilder = builder;
            return this;
        }

        public Builder objectMapper(ObjectMapper mapper) {
            this.json = mapper;
            return this;
        }

        public Builder responseTimeout(Duration timeout) {
            this.responseTimeout = timeout;
            return this;
        }

        public Builder maxInMemoryBytes(int bytes) {
            this.maxInMemoryBytes = bytes;
            return this;
        }

        public Builder userAgent(String agent) {
            this.userAgent = agent;
            return this;
        }

        public PlatformClient build() {
            Objects.requireNonNull(baseUrl, "baseUrl");
            return new PlatformClient(this);
        }
    }

    // identity

    @Override
    public Mono<Identity> me() {
        return get(PlatformOperation.GET_ME, Map.of(), Map.of(), Identity.class);
    }

    @Override
    public Mono<List<ServiceAccount>> listServiceAccounts() {
        return body(PlatformOperation.LIST_SERVICE_ACCOUNTS, Map.of(), Map.of(), RequestOptions.none(), null, SERVICE_ACCOUNTS);
    }

    @Override
    public Mono<ServiceAccountCreated> createServiceAccount(CreateServiceAccount request) {
        return body(PlatformOperation.CREATE_SERVICE_ACCOUNT, Map.of(), Map.of(), RequestOptions.none(), request, ServiceAccountCreated.class);
    }

    @Override
    public Mono<ServiceAccountRotated> rotateServiceAccount(String id, RequestOptions options) {
        return body(PlatformOperation.ROTATE_SERVICE_ACCOUNT, Map.of("id", id), Map.of(), options, null, ServiceAccountRotated.class);
    }

    @Override
    public Mono<Void> deleteServiceAccount(String id, RequestOptions options) {
        return exchange(PlatformOperation.DELETE_SERVICE_ACCOUNT, request(PlatformOperation.DELETE_SERVICE_ACCOUNT, Map.of("id", id), Map.of(), options),
                (ParameterizedTypeReference<Void>) null).then();
    }

    // resource types

    @Override
    public Mono<Conditional<List<ResourceType>>> listResourceTypes(String etag) {
        return conditional(PlatformOperation.LIST_RESOURCE_TYPES, Map.of(), etag, RESOURCE_TYPES);
    }

    @Override
    public Mono<Conditional<List<PriceList>>> listPriceLists(String etag) {
        return conditional(PlatformOperation.LIST_PRICE_LISTS, Map.of(), etag, PRICE_LISTS);
    }

    @Override
    public Mono<PriceList> getPriceList(String name) {
        return get(PlatformOperation.GET_PRICE_LIST, Map.of("name", name), Map.of(), PriceList.class);
    }

    @Override
    public Mono<Conditional<ResourceType>> getResourceType(String name, String etag) {
        return conditional(PlatformOperation.GET_RESOURCE_TYPE, Map.of("name", name), etag, new ParameterizedTypeReference<ResourceType>() {
        });
    }

    @Override
    public Mono<ObserveListPage> observeList(String typeName, ObserveListRequest request) {
        return body(PlatformOperation.OBSERVE_LIST_RESOURCE_TYPE, Map.of("name", typeName), Map.of(), RequestOptions.none(), request,
                ObserveListPage.class);
    }

    @Override
    public Mono<ObserveListPage> discover(String typeName, DiscoverRequest request) {
        return body(PlatformOperation.DISCOVER_RESOURCE_TYPE, Map.of("name", typeName), Map.of(), RequestOptions.none(), request, ObserveListPage.class);
    }

    // catalog and regions

    @Override
    public Mono<List<CatalogEntry>> listCatalog(String noun) {
        return body(PlatformOperation.LIST_CATALOG, Map.of(), query("noun", noun), RequestOptions.none(), null, CATALOG);
    }

    @Override
    public Mono<List<Region>> listRegions() {
        return body(PlatformOperation.LIST_REGIONS, Map.of(), Map.of(), RequestOptions.none(), null, REGIONS);
    }

    @Override
    public Mono<Region> getRegion(String name) {
        return get(PlatformOperation.GET_REGION, Map.of("name", name), Map.of(), Region.class);
    }

    @Override
    public Mono<RegionAvailability> getRegionAvailability(String name) {
        return get(PlatformOperation.GET_REGION_AVAILABILITY, Map.of("name", name), Map.of(), RegionAvailability.class);
    }

    // resources

    @Override
    public Mono<PagedList<Resource>> listResources(ResourceFilter filter) {
        return paged(PlatformOperation.LIST_RESOURCES, Map.of(), resourceQuery(filter), RESOURCES);
    }

    @Override
    public Mono<Resource> getResourceByWfrn(String wfrn) {
        return get(PlatformOperation.GET_RESOURCE_BY_WFRN, Map.of(), query("wfrn", wfrn), Resource.class);
    }

    @Override
    public Mono<ObserveResult> observeResourceByWfrn(String wfrn) {
        return body(PlatformOperation.OBSERVE_RESOURCE_BY_WFRN, Map.of(), query("wfrn", wfrn), RequestOptions.none(), null, ObserveResult.class);
    }

    @Override
    public Mono<Resource> getResource(String id) {
        return get(PlatformOperation.GET_RESOURCE, Map.of("id", id), Map.of(), Resource.class);
    }

    @Override
    public Mono<Lineage> getResourceLineage(String id) {
        return get(PlatformOperation.GET_RESOURCE_LINEAGE, Map.of("id", id), Map.of(), Lineage.class);
    }

    @Override
    public Mono<ObserveResult> observeResource(String id) {
        return body(PlatformOperation.OBSERVE_RESOURCE, Map.of("id", id), Map.of(), RequestOptions.none(), null, ObserveResult.class);
    }

    @Override
    public Mono<MetricsResult> getResourceMetrics(String id, MetricsRequest request) {
        return body(PlatformOperation.GET_RESOURCE_METRICS, Map.of("id", id), Map.of(), RequestOptions.none(), request, MetricsResult.class);
    }

    @Override
    public Mono<ObserveBatchResult> observeResources(ObserveBatchRequest request) {
        return body(PlatformOperation.OBSERVE_RESOURCES, Map.of(), Map.of(), RequestOptions.none(), request, ObserveBatchResult.class);
    }

    @Override
    public Mono<Resource> rehomeResource(String id, RehomeRequest request, RequestOptions options) {
        return body(PlatformOperation.REHOME_RESOURCE, Map.of("id", id), Map.of(), options, request, Resource.class);
    }

    @Override
    public Mono<BackfillResult> backfillWfrns(BackfillRequest request, RequestOptions options) {
        return body(PlatformOperation.BACKFILL_WFRNS, Map.of(), Map.of(), options, request, BackfillResult.class);
    }

    @Override
    public Mono<CollisionReport> findResourceCollisions() {
        return body(PlatformOperation.FIND_RESOURCE_COLLISIONS, Map.of(), Map.of(), RequestOptions.none(), null, CollisionReport.class);
    }

    // jobs

    @Override
    public Mono<PagedList<JobSummary>> listJobs(JobFilter filter) {
        Map<String, Object> q = new LinkedHashMap<>();
        put(q, "workflow", filter.workflow());
        put(q, "status", filter.status());
        put(q, "parent", filter.parent());
        put(q, "limit", filter.limit());
        put(q, "account", filter.account());
        put(q, "region", filter.region());
        put(q, "wfrn", filter.wfrn());
        put(q, "consoleRequestId", filter.consoleRequestId());
        filter.labels().forEach((name, value) -> put(q, "label." + name, value));
        put(q, "updatedAfter", filter.updatedAfter());
        put(q, "after", filter.after());
        return paged(PlatformOperation.LIST_JOBS, Map.of(), q, JOBS);
    }

    @Override
    public Mono<Accepted<CommandAccepted>> startJob(StartJobRequest request, RequestOptions options) {
        return accepted(PlatformOperation.START_JOB, Map.of(), options, request, CommandAccepted.class);
    }

    @Override
    public Mono<Accepted<CommandAccepted>> startBatch(StartBatchRequest request, RequestOptions options) {
        return accepted(PlatformOperation.START_BATCH, Map.of(), options, request, CommandAccepted.class);
    }

    @Override
    public Flux<FeedEvent> streamJobs(Long after, Long lastEventId) {
        return sse(PlatformOperation.STREAM_JOBS, Map.of(), query("after", after), lastEventId, FEED_SSE);
    }

    @Override
    public Mono<JobDetail> getJob(String id) {
        return get(PlatformOperation.GET_JOB, Map.of("id", id), Map.of(), JobDetail.class);
    }

    @Override
    public Flux<JobEvent> streamJobEvents(String id) {
        return sse(PlatformOperation.STREAM_JOB_EVENTS, Map.of("id", id), Map.of(), null, JOB_SSE);
    }

    @Override
    public Mono<Accepted<CommandAccepted>> cancelJob(String id, RequestOptions options) {
        return accepted(PlatformOperation.CANCEL_JOB, Map.of("id", id), options, null, CommandAccepted.class);
    }

    @Override
    public Mono<Accepted<CommandAccepted>> retryJob(String id, RetryRequest request, RequestOptions options) {
        return accepted(PlatformOperation.RETRY_JOB, Map.of("id", id), options, request == null ? Map.of() : request, CommandAccepted.class);
    }

    @Override
    public Mono<Accepted<CommandAccepted>> skipJobStep(String id, SkipRequest request, RequestOptions options) {
        return accepted(PlatformOperation.SKIP_JOB_STEP, Map.of("id", id), options, request, CommandAccepted.class);
    }

    @Override
    public Mono<Accepted<CommandAccepted>> abortJob(String id, RequestOptions options) {
        return accepted(PlatformOperation.ABORT_JOB, Map.of("id", id), options, null, CommandAccepted.class);
    }

    @Override
    public Mono<Accepted<CommandAccepted>> rerunJob(String id, RerunRequest request, RequestOptions options) {
        return accepted(PlatformOperation.RERUN_JOB, Map.of("id", id), options, request, CommandAccepted.class);
    }

    @Override
    public Mono<Accepted<CommandAccepted>> retryFailedRows(String id, RequestOptions options) {
        return accepted(PlatformOperation.RETRY_FAILED_ROWS, Map.of("id", id), options, null, CommandAccepted.class);
    }

    // approvals

    @Override
    public Mono<List<Approval>> listApprovals(String status) {
        return body(PlatformOperation.LIST_APPROVALS, Map.of(), query("status", status), RequestOptions.none(), null, APPROVALS);
    }

    @Override
    public Mono<Approval> getApproval(String id) {
        return get(PlatformOperation.GET_APPROVAL, Map.of("id", id), Map.of(), Approval.class);
    }

    @Override
    public Mono<Accepted<CommandAccepted>> approve(String id, DecisionRequest request, RequestOptions options) {
        return accepted(PlatformOperation.APPROVE_APPROVAL, Map.of("id", id), options, request == null ? Map.of() : request, CommandAccepted.class);
    }

    @Override
    public Mono<Accepted<CommandAccepted>> reject(String id, DecisionRequest request, RequestOptions options) {
        return accepted(PlatformOperation.REJECT_APPROVAL, Map.of("id", id), options, request == null ? Map.of() : request, CommandAccepted.class);
    }

    // events

    @Override
    public Mono<EventPage> listEvents(Long after, Integer limit, List<String> types) {
        Map<String, Object> q = new LinkedHashMap<>();
        put(q, "after", after);
        put(q, "limit", limit);
        put(q, "types", types == null || types.isEmpty() ? null : String.join(",", types));
        return get(PlatformOperation.LIST_EVENTS, Map.of(), q, EventPage.class);
    }

    @Override
    public Flux<FeedEvent> streamEvents(Long after, Long lastEventId, List<String> types) {
        Map<String, Object> q = new LinkedHashMap<>();
        put(q, "after", after);
        put(q, "types", types == null || types.isEmpty() ? null : String.join(",", types));
        return sse(PlatformOperation.STREAM_EVENTS, Map.of(), q, lastEventId, FEED_SSE);
    }

    @Override
    public Flux<FeedEvent> followEvents(long fromSeq, List<String> types) {
        AtomicLong last = new AtomicLong(fromSeq);
        return Flux.defer(() -> streamEvents(last.get(), null, types))
                .doOnNext(event -> last.accumulateAndGet(event.seq(), Math::max))
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(30))
                        .filter(e -> !(e instanceof PlatformException pe) || pe.retryable() || pe.status() >= 500)
                        .transientErrors(true))
                .repeat();
    }

    @Override
    public Mono<Snapshot<Resource>> snapshotResources(Long updatedAfter, String cursor, Integer limit) {
        return body(PlatformOperation.GET_EVENTS_SNAPSHOT, Map.of(), snapshotQuery("resources", updatedAfter, cursor, limit), RequestOptions.none(), null,
                RESOURCE_SNAPSHOT);
    }

    @Override
    public Mono<Snapshot<JobSummary>> snapshotJobs(Long updatedAfter, String cursor, Integer limit) {
        return body(PlatformOperation.GET_EVENTS_SNAPSHOT, Map.of(), snapshotQuery("jobs", updatedAfter, cursor, limit), RequestOptions.none(), null,
                JOB_SNAPSHOT);
    }

    // quotas

    @Override
    public Mono<Quota> getQuota(String key) {
        return get(PlatformOperation.GET_QUOTA, Map.of("key", key), Map.of(), Quota.class);
    }

    @Override
    public Mono<Quota> putQuota(String key, QuotaUpdate update, RequestOptions options) {
        return body(PlatformOperation.PUT_QUOTA, Map.of("key", key), Map.of(), options, update, Quota.class);
    }

    // audit

    @Override
    public Mono<List<AuditRow>> listAudit(AuditFilter filter) {
        Map<String, Object> q = new LinkedHashMap<>();
        put(q, "workflow", filter.workflow());
        put(q, "from", filter.from());
        put(q, "to", filter.to());
        put(q, "requester", filter.requester());
        put(q, "account", filter.account());
        put(q, "wfrn", filter.wfrn());
        return body(PlatformOperation.LIST_AUDIT, Map.of(), q, RequestOptions.none(), null, AUDIT_ROWS);
    }

    @Override
    public Mono<EvidenceRecord> getEvidenceRecord(String jobId) {
        return get(PlatformOperation.GET_EVIDENCE_RECORD, Map.of("jobId", jobId), Map.of(), EvidenceRecord.class);
    }

    @Override
    public Mono<Verification> verifyEvidenceRecord(String jobId) {
        return get(PlatformOperation.VERIFY_EVIDENCE_RECORD, Map.of("jobId", jobId), Map.of(), Verification.class);
    }

    @Override
    public Mono<byte[]> exportEvidenceRecord(String jobId, String format) {
        return bytes(PlatformOperation.EXPORT_EVIDENCE_RECORD, Map.of("jobId", jobId), query("format", format), null);
    }

    @Override
    public Mono<byte[]> exportEvidencePack(ExportPackRequest request) {
        return bytes(PlatformOperation.EXPORT_EVIDENCE_PACK, Map.of(), Map.of(), request);
    }

    // generated routes

    @Override
    public Mono<Accepted<JobAccepted>> start(PlatformOperation route, Map<String, String> pathParams, Map<String, Object> input, RequestOptions options) {
        expect(route, PlatformOperation.Kind.START);
        return accepted(route, pathParams, options, input == null ? Map.of() : input, JobAccepted.class);
    }

    @Override
    public Mono<Accepted<JobAccepted>> start(CatalogEntry entry, Map<String, String> pathParams, Map<String, Object> input, RequestOptions options) {
        return exchange(null, request(HttpMethod.valueOf(entry.method().toUpperCase()), entry.path(), pathParams, Map.of(), options)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(input == null ? Map.of() : input), ref(JobAccepted.class))
                .map(PlatformClient::acceptedOf);
    }

    @Override
    public Mono<Preview> preview(PlatformOperation route, Map<String, String> pathParams, Map<String, Object> input, RequestOptions options) {
        expect(route, PlatformOperation.Kind.PREVIEW);
        return body(route, pathParams, Map.of(), options, input == null ? Map.of() : input, Preview.class);
    }

    @Override
    public Mono<Preview> preview(CatalogEntry entry, Map<String, String> pathParams, Map<String, Object> input, RequestOptions options) {
        if (!entry.hasPreview()) {
            return Mono.error(new IllegalArgumentException("workflow " + entry.workflow() + " has no preview route"));
        }
        return exchange(null, request(HttpMethod.POST, entry.previewPath(), pathParams, Map.of(), options).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(input == null ? Map.of() : input), ref(Preview.class)).map(Reply::body);
    }

    @Override
    public Mono<Accepted<BatchAccepted>> batch(PlatformOperation route, List<Map<String, Object>> items, Map<String, Object> defaults,
            RolloutPolicy rollout, List<BatchContext> contexts, RequestOptions options) {
        expect(route, PlatformOperation.Kind.BATCH);
        return accepted(route, Map.of(), options, batchBody(items, defaults, rollout, contexts), BatchAccepted.class);
    }

    @Override
    public Mono<Accepted<BatchAccepted>> batch(CatalogEntry entry, List<Map<String, Object>> items, Map<String, Object> defaults, RolloutPolicy rollout,
            List<BatchContext> contexts, RequestOptions options) {
        return exchange(null, request(HttpMethod.POST, entry.batchPath(), Map.of(), Map.of(), options).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(batchBody(items, defaults, rollout, contexts)), ref(BatchAccepted.class)).map(PlatformClient::acceptedOf);
    }

    @Override
    public Mono<PagedList<Resource>> listOfType(String plural, ResourceFilter filter) {
        return exchange(null, request(HttpMethod.GET, "/api/v1/" + plural, Map.of(), resourceQuery(filter), RequestOptions.none()), RESOURCES)
                .map(PlatformClient::pageOf);
    }

    @Override
    public Mono<Resource> getOfType(String plural, String id) {
        return exchange(null, request(HttpMethod.GET, "/api/v1/" + plural + "/{id}", Map.of("id", id), Map.of(), RequestOptions.none()),
                ref(Resource.class)).map(Reply::body);
    }

    // the plumbing

    /** One answer of the platform: status, headers and the parsed body (null for 204 and 304). */
    record Reply<T>(int status, HttpHeaders headers, T body) {

        String header(String name) {
            return headers.getFirst(name);
        }
    }

    private static <T> ParameterizedTypeReference<T> ref(Class<T> type) {
        return ParameterizedTypeReference.forType(type);
    }

    private static void expect(PlatformOperation route, PlatformOperation.Kind kind) {
        if (route.kind() != kind) {
            throw new IllegalArgumentException(route.operationId() + " is not a " + kind.name().toLowerCase() + " route");
        }
    }

    private static Map<String, Object> batchBody(List<Map<String, Object>> items, Map<String, Object> defaults, RolloutPolicy rollout,
            List<BatchContext> contexts) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items == null ? List.of() : items);
        if (defaults != null && !defaults.isEmpty()) {
            body.put("defaults", defaults);
        }
        if (rollout != null) {
            body.put("rollout", rollout);
        }
        if (contexts != null && !contexts.isEmpty()) {
            body.put("contexts", contexts);
        }
        return body;
    }

    private static Map<String, Object> resourceQuery(ResourceFilter filter) {
        Map<String, Object> q = new LinkedHashMap<>();
        put(q, "type", filter.type());
        put(q, "owner", filter.owner());
        put(q, "status", filter.status());
        put(q, "account", filter.account());
        put(q, "wfrn", filter.wfrn());
        put(q, "visibility", filter.visibility());
        put(q, "limit", filter.limit());
        put(q, "after", filter.after());
        return q;
    }

    private static Map<String, Object> snapshotQuery(String kind, Long updatedAfter, String cursor, Integer limit) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("kind", kind);
        put(q, "updatedAfter", updatedAfter);
        put(q, "cursor", cursor);
        put(q, "limit", limit);
        return q;
    }

    private static Map<String, Object> query(String name, Object value) {
        Map<String, Object> q = new LinkedHashMap<>();
        put(q, name, value);
        return q;
    }

    private static void put(Map<String, Object> q, String name, Object value) {
        if (value != null) {
            q.put(name, value);
        }
    }

    private WebClient.RequestBodySpec request(PlatformOperation op, Map<String, String> pathVars, Map<String, Object> query, RequestOptions options) {
        return request(op.method(), op.path(), pathVars, query, options);
    }

    private WebClient.RequestBodySpec request(HttpMethod method, String path, Map<String, String> pathVars, Map<String, Object> query,
            RequestOptions options) {
        return web.method(method)
                .uri(uri -> expand(uri, path, pathVars, query))
                .headers(options::apply);
    }

    private static java.net.URI expand(UriBuilder uri, String path, Map<String, String> pathVars, Map<String, Object> query) {
        uri.path(path);
        query.forEach((name, value) -> uri.queryParam(name, value));
        return uri.build(pathVars == null ? Map.of() : pathVars);
    }

    private <T> Mono<T> get(PlatformOperation op, Map<String, String> pathVars, Map<String, Object> query, Class<T> type) {
        return exchange(op, request(op, pathVars, query, RequestOptions.none()), ref(type)).map(Reply::body);
    }

    private <T> Mono<T> body(PlatformOperation op, Map<String, String> pathVars, Map<String, Object> query, RequestOptions options, Object body,
            Class<T> type) {
        return body(op, pathVars, query, options, body, ref(type));
    }

    private <T> Mono<T> body(PlatformOperation op, Map<String, String> pathVars, Map<String, Object> query, RequestOptions options, Object body,
            ParameterizedTypeReference<T> type) {
        return exchange(op, withBody(request(op, pathVars, query, options), body), type).map(Reply::body);
    }

    private static WebClient.RequestHeadersSpec<?> withBody(WebClient.RequestBodySpec spec, Object body) {
        return body == null ? spec : spec.contentType(MediaType.APPLICATION_JSON).bodyValue(body);
    }

    private <T> Mono<Accepted<T>> accepted(PlatformOperation op, Map<String, String> pathVars, RequestOptions options, Object body, Class<T> type) {
        return exchange(op, withBody(request(op, pathVars, Map.of(), options), body), ref(type)).map(PlatformClient::acceptedOf);
    }

    private static <T> Accepted<T> acceptedOf(Reply<T> reply) {
        return new Accepted<>(reply.body(), "true".equalsIgnoreCase(reply.header(PlatformHeaders.IDEMPOTENT_REPLAY)),
                reply.header(PlatformHeaders.CORRELATION_ID));
    }

    private <T> Mono<PagedList<T>> paged(PlatformOperation op, Map<String, String> pathVars, Map<String, Object> query,
            ParameterizedTypeReference<List<T>> type) {
        return exchange(op, request(op, pathVars, query, RequestOptions.none()), type).map(PlatformClient::pageOf);
    }

    private static <T> PagedList<T> pageOf(Reply<List<T>> reply) {
        return new PagedList<>(reply.body(), LinkHeaders.nextCursor(reply.headers().get(PlatformHeaders.LINK)).orElse(null));
    }

    private <T> Mono<Conditional<T>> conditional(PlatformOperation op, Map<String, String> pathVars, String etag, ParameterizedTypeReference<T> type) {
        WebClient.RequestBodySpec spec = request(op, pathVars, Map.of(), RequestOptions.none());
        if (etag != null && !etag.isBlank()) {
            spec = spec.header(PlatformHeaders.IF_NONE_MATCH, etag);
        }
        return exchange(op, spec, type).map(reply -> reply.status() == 304 ? Conditional.notModified(firstNonNull(reply.header(PlatformHeaders.ETAG), etag))
                : Conditional.of(reply.body(), reply.header(PlatformHeaders.ETAG)));
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    private Mono<byte[]> bytes(PlatformOperation op, Map<String, String> pathVars, Map<String, Object> query, Object body) {
        return exchange(op, withBody(request(op, pathVars, query, RequestOptions.none()), body), ref(byte[].class)).map(Reply::body);
    }

    private <T> Mono<Reply<T>> exchange(PlatformOperation op, WebClient.RequestHeadersSpec<?> spec, ParameterizedTypeReference<T> type) {
        return spec.exchangeToMono(response -> reply(op, response, type));
    }

    private <T> Mono<Reply<T>> reply(PlatformOperation op, ClientResponse response, ParameterizedTypeReference<T> type) {
        int status = response.statusCode().value();
        HttpHeaders headers = response.headers().asHttpHeaders();
        if (status == 304) {
            return response.releaseBody().thenReturn(new Reply<>(status, headers, null));
        }
        if (response.statusCode().is2xxSuccessful()) {
            if (status == 204 || type == null) {
                return response.releaseBody().thenReturn(new Reply<>(status, headers, null));
            }
            return response.bodyToMono(type).map(body -> new Reply<>(status, headers, body)).defaultIfEmpty(new Reply<>(status, headers, null));
        }
        return response.bodyToMono(String.class).defaultIfEmpty("").flatMap(text -> Mono.<Reply<T>>error(failure(op, status, headers, text)));
    }

    private PlatformException failure(PlatformOperation op, int status, HttpHeaders headers, String text) {
        PlatformError error = null;
        if (text != null && !text.isBlank()) {
            try {
                error = json.readValue(text, PlatformError.class);
            } catch (Exception e) {
                error = new PlatformError(text.length() > 300 ? text.substring(0, 300) : text, null, null, null, null, null);
            }
        }
        Integer retryAfter = null;
        String header = headers.getFirst(PlatformHeaders.RETRY_AFTER);
        if (header != null) {
            try {
                retryAfter = Integer.valueOf(header.trim());
            } catch (NumberFormatException e) {
                retryAfter = null;
            }
        }
        if (retryAfter == null && error != null) {
            retryAfter = error.retryAfter();
        }
        String correlationId = error != null && error.correlationId() != null ? error.correlationId() : headers.getFirst(PlatformHeaders.CORRELATION_ID);
        return new PlatformException(op, status, error, retryAfter, correlationId);
    }

    private <T> Flux<T> sse(PlatformOperation op, Map<String, String> pathVars, Map<String, Object> query, Long lastEventId,
            ParameterizedTypeReference<ServerSentEvent<T>> type) {
        return streams.method(op.method())
                .uri(uri -> expand(uri, op.path(), pathVars, query))
                .accept(MediaType.TEXT_EVENT_STREAM)
                .headers(h -> {
                    if (lastEventId != null) {
                        h.set(PlatformHeaders.LAST_EVENT_ID, String.valueOf(lastEventId));
                    }
                })
                .exchangeToFlux(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.bodyToFlux(type);
                    }
                    return response.bodyToMono(String.class).defaultIfEmpty("")
                            .flatMapMany(text -> Flux.<ServerSentEvent<T>>error(failure(op, response.statusCode().value(),
                                    response.headers().asHttpHeaders(), text)));
                })
                .filter(event -> event.data() != null)
                .map(ServerSentEvent::data);
    }

    /** The mapper this client reads and writes with. */
    public ObjectMapper json() {
        return json;
    }

    /** Applies a function to every request's WebClient; for tests. */
    public <R> R with(Function<WebClient, R> f) {
        return f.apply(web);
    }
}
