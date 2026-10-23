package com.iaf.console.platform.client;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpMethod;

/**
 * Every operation of the target contract the console uses, by operation id, method and path.
 * The client builds every request from one of these, so the contract test can check each one
 * against {@code openapi-target.yaml} and against {@code used-operations.txt}.
 */
public enum PlatformOperation {

    // identity
    GET_ME("getMe", HttpMethod.GET, "/api/v1/me"),
    LIST_SERVICE_ACCOUNTS("listServiceAccounts", HttpMethod.GET, "/api/v1/service-accounts"),
    CREATE_SERVICE_ACCOUNT("createServiceAccount", HttpMethod.POST, "/api/v1/service-accounts"),
    DELETE_SERVICE_ACCOUNT("deleteServiceAccount", HttpMethod.DELETE, "/api/v1/service-accounts/{id}"),
    ROTATE_SERVICE_ACCOUNT("rotateServiceAccount", HttpMethod.POST, "/api/v1/service-accounts/{id}:rotate"),

    // resource types
    LIST_RESOURCE_TYPES("listResourceTypes", HttpMethod.GET, "/api/v1/resource-types"),
    GET_RESOURCE_TYPE("getResourceType", HttpMethod.GET, "/api/v1/resource-types/{name}"),
    OBSERVE_LIST_RESOURCE_TYPE("observeListResourceType", HttpMethod.POST, "/api/v1/resource-types/{name}:observe-list"),
    DISCOVER_RESOURCE_TYPE("discoverResourceType", HttpMethod.POST, "/api/v1/resource-types/{name}:discover"),

    // catalog and regions
    LIST_CATALOG("listCatalog", HttpMethod.GET, "/api/v1/catalog"),
    LIST_REGIONS("listRegions", HttpMethod.GET, "/api/v1/regions"),
    GET_REGION("getRegion", HttpMethod.GET, "/api/v1/regions/{name}"),
    GET_REGION_AVAILABILITY("getRegionAvailability", HttpMethod.GET, "/api/v1/regions/{name}/availability"),

    // resources
    LIST_RESOURCES("listResources", HttpMethod.GET, "/api/v1/resources"),
    GET_RESOURCE_BY_WFRN("getResourceByWfrn", HttpMethod.GET, "/api/v1/resources/by-wfrn"),
    OBSERVE_RESOURCE_BY_WFRN("observeResourceByWfrn", HttpMethod.POST, "/api/v1/resources/by-wfrn:observe"),
    GET_RESOURCE("getResource", HttpMethod.GET, "/api/v1/resources/{id}"),
    GET_RESOURCE_LINEAGE("getResourceLineage", HttpMethod.GET, "/api/v1/resources/{id}/lineage"),
    OBSERVE_RESOURCE("observeResource", HttpMethod.POST, "/api/v1/resources/{id}:observe"),
    GET_RESOURCE_METRICS("getResourceMetrics", HttpMethod.POST, "/api/v1/resources/{id}:metrics"),
    OBSERVE_RESOURCES("observeResources", HttpMethod.POST, "/api/v1/resources:observe"),
    REHOME_RESOURCE("rehomeResource", HttpMethod.POST, "/api/v1/resources/{id}:rehome"),
    BACKFILL_WFRNS("backfillWfrns", HttpMethod.POST, "/api/v1/resources:backfill-wfrn"),
    FIND_RESOURCE_COLLISIONS("findResourceCollisions", HttpMethod.POST, "/api/v1/resources:find-collisions"),

    // jobs
    LIST_JOBS("listJobs", HttpMethod.GET, "/api/v1/jobs"),
    START_JOB("startJob", HttpMethod.POST, "/api/v1/jobs"),
    START_BATCH("startBatch", HttpMethod.POST, "/api/v1/jobs:batch"),
    STREAM_JOBS("streamJobs", HttpMethod.GET, "/api/v1/jobs/events/stream"),
    GET_JOB("getJob", HttpMethod.GET, "/api/v1/jobs/{id}"),
    STREAM_JOB_EVENTS("streamJobEvents", HttpMethod.GET, "/api/v1/jobs/{id}/events"),
    CANCEL_JOB("cancelJob", HttpMethod.POST, "/api/v1/jobs/{id}:cancel"),
    RETRY_JOB("retryJob", HttpMethod.POST, "/api/v1/jobs/{id}:retry"),
    SKIP_JOB_STEP("skipJobStep", HttpMethod.POST, "/api/v1/jobs/{id}:skip"),
    ABORT_JOB("abortJob", HttpMethod.POST, "/api/v1/jobs/{id}:abort"),
    RERUN_JOB("rerunJob", HttpMethod.POST, "/api/v1/jobs/{id}:rerun"),
    RETRY_FAILED_ROWS("retryFailedRows", HttpMethod.POST, "/api/v1/jobs/{id}:retry-failed-rows"),

    // approvals
    LIST_APPROVALS("listApprovals", HttpMethod.GET, "/api/v1/approvals"),
    GET_APPROVAL("getApproval", HttpMethod.GET, "/api/v1/approvals/{id}"),
    APPROVE_APPROVAL("approveApproval", HttpMethod.POST, "/api/v1/approvals/{id}:approve"),
    REJECT_APPROVAL("rejectApproval", HttpMethod.POST, "/api/v1/approvals/{id}:reject"),

    // events
    LIST_EVENTS("listEvents", HttpMethod.GET, "/api/v1/events"),
    STREAM_EVENTS("streamEvents", HttpMethod.GET, "/api/v1/events/stream"),
    GET_EVENTS_SNAPSHOT("getEventsSnapshot", HttpMethod.GET, "/api/v1/events/snapshot"),

    // quotas
    GET_QUOTA("getQuota", HttpMethod.GET, "/api/v1/quotas/{key}"),
    PUT_QUOTA("putQuota", HttpMethod.PUT, "/api/v1/quotas/{key}"),

    // audit
    LIST_AUDIT("listAudit", HttpMethod.GET, "/api/v1/audit"),
    GET_EVIDENCE_RECORD("getEvidenceRecord", HttpMethod.GET, "/api/v1/audit/{jobId}"),
    VERIFY_EVIDENCE_RECORD("verifyEvidenceRecord", HttpMethod.GET, "/api/v1/audit/{jobId}:verify"),
    EXPORT_EVIDENCE_RECORD("exportEvidenceRecord", HttpMethod.GET, "/api/v1/audit/{jobId}/export"),
    EXPORT_EVIDENCE_PACK("exportEvidencePack", HttpMethod.POST, "/api/v1/audit:export-pack"),

    // generated routes of the compute workflows
    CREATE_VM("createVm", HttpMethod.POST, "/api/v1/vms", Kind.START, "create-vm", "vm"),
    LIST_VMS("listVms", HttpMethod.GET, "/api/v1/vms", Kind.LIST, null, "vm"),
    GET_VM("getVm", HttpMethod.GET, "/api/v1/vms/{id}", Kind.GET, null, "vm"),
    CREATE_VM_PREVIEW("createVmPreview", HttpMethod.POST, "/api/v1/vms:preview", Kind.PREVIEW, "create-vm", "vm"),
    CREATE_VM_BATCH("createVmBatch", HttpMethod.POST, "/api/v1/vms:batch", Kind.BATCH, "create-vm", "vm"),
    DELETE_VM("deleteVm", HttpMethod.DELETE, "/api/v1/vms/{vmId}", Kind.START, "delete-vm", "vm"),
    UPDATE_VM("updateVm", HttpMethod.PATCH, "/api/v1/vms/{vmId}", Kind.START, "update-vm", "vm"),
    DELETE_VM_PREVIEW("deleteVmPreview", HttpMethod.POST, "/api/v1/vms/{vmId}:preview", Kind.PREVIEW, "delete-vm", "vm"),
    DELETE_VM_BATCH("deleteVmBatch", HttpMethod.POST, "/api/v1/vms:delete:batch", Kind.BATCH, "delete-vm", "vm"),
    UPDATE_VM_PREVIEW("updateVmPreview", HttpMethod.POST, "/api/v1/vms/{vmId}:update:preview", Kind.PREVIEW, "update-vm", "vm"),
    UPDATE_VM_BATCH("updateVmBatch", HttpMethod.POST, "/api/v1/vms:update:batch", Kind.BATCH, "update-vm", "vm"),
    PATCH_VM("patchVm", HttpMethod.POST, "/api/v1/vms/{vmId}:patch", Kind.START, "patch-cve", "vm"),
    PATCH_VM_PREVIEW("patchVmPreview", HttpMethod.POST, "/api/v1/vms/{vmId}:patch:preview", Kind.PREVIEW, "patch-cve", "vm"),
    PATCH_VM_BATCH("patchVmBatch", HttpMethod.POST, "/api/v1/vms:patch:batch", Kind.BATCH, "patch-cve", "vm"),
    RESTART_VM("restartVm", HttpMethod.POST, "/api/v1/vms/{vmId}:restart", Kind.START, "vm-restart", "vm"),
    RESTART_VM_PREVIEW("restartVmPreview", HttpMethod.POST, "/api/v1/vms/{vmId}:restart:preview", Kind.PREVIEW, "vm-restart", "vm"),
    RESTART_VM_BATCH("restartVmBatch", HttpMethod.POST, "/api/v1/vms:restart:batch", Kind.BATCH, "vm-restart", "vm"),

    // generated routes of the data workflows
    CREATE_DATABASE("createDatabase", HttpMethod.POST, "/api/v1/databases", Kind.START, "create-database", "database"),
    LIST_DATABASES("listDatabases", HttpMethod.GET, "/api/v1/databases", Kind.LIST, null, "database"),
    GET_DATABASE("getDatabase", HttpMethod.GET, "/api/v1/databases/{id}", Kind.GET, null, "database"),
    CREATE_DATABASE_PREVIEW("createDatabasePreview", HttpMethod.POST, "/api/v1/databases:preview", Kind.PREVIEW, "create-database", "database"),
    CREATE_DATABASE_BATCH("createDatabaseBatch", HttpMethod.POST, "/api/v1/databases:batch", Kind.BATCH, "create-database", "database"),
    UPGRADE_MONGODB_CLUSTER("upgradeMongodbCluster", HttpMethod.POST, "/api/v1/mongodb-clusters/{clusterId}:upgrade", Kind.START,
            "mongodb-upgrade", "database"),
    UPGRADE_MONGODB_CLUSTER_PREVIEW("upgradeMongodbClusterPreview", HttpMethod.POST, "/api/v1/mongodb-clusters/{clusterId}:upgrade:preview",
            Kind.PREVIEW, "mongodb-upgrade", "database"),
    UPGRADE_MONGODB_CLUSTER_BATCH("upgradeMongodbClusterBatch", HttpMethod.POST, "/api/v1/mongodb-clusters:upgrade:batch", Kind.BATCH,
            "mongodb-upgrade", "database"),

    // generated routes of the platform team's workflows
    LIST_IMAGES("listImages", HttpMethod.GET, "/api/v1/images", Kind.LIST, null, "image"),
    GET_IMAGE("getImage", HttpMethod.GET, "/api/v1/images/{id}", Kind.GET, null, "image"),
    BUILD_IMAGE("buildImage", HttpMethod.POST, "/api/v1/images:build", Kind.START, "build-vm-image", "image"),
    BUILD_IMAGE_BATCH("buildImageBatch", HttpMethod.POST, "/api/v1/images:build:batch", Kind.BATCH, "build-vm-image", "image"),
    CREATE_VOLUME("createVolume", HttpMethod.POST, "/api/v1/volumes", Kind.START, "request-storage", "volume"),
    LIST_VOLUMES("listVolumes", HttpMethod.GET, "/api/v1/volumes", Kind.LIST, null, "volume"),
    GET_VOLUME("getVolume", HttpMethod.GET, "/api/v1/volumes/{id}", Kind.GET, null, "volume"),
    CREATE_VOLUME_BATCH("createVolumeBatch", HttpMethod.POST, "/api/v1/volumes:batch", Kind.BATCH, "request-storage", "volume"),
    CREATE_ADDRESS("createAddress", HttpMethod.POST, "/api/v1/addresses", Kind.START, "reserve-ip-address", "address"),
    CREATE_ADDRESS_BATCH("createAddressBatch", HttpMethod.POST, "/api/v1/addresses:batch", Kind.BATCH, "reserve-ip-address", "address"),
    CREATE_ENVIRONMENT("createEnvironment", HttpMethod.POST, "/api/v1/environments", Kind.START, "web-environment", "environment"),
    LIST_ENVIRONMENTS("listEnvironments", HttpMethod.GET, "/api/v1/environments", Kind.LIST, null, "environment"),
    GET_ENVIRONMENT("getEnvironment", HttpMethod.GET, "/api/v1/environments/{id}", Kind.GET, null, "environment"),
    CREATE_ENVIRONMENT_PREVIEW("createEnvironmentPreview", HttpMethod.POST, "/api/v1/environments:preview", Kind.PREVIEW, "web-environment",
            "environment"),
    CREATE_ENVIRONMENT_BATCH("createEnvironmentBatch", HttpMethod.POST, "/api/v1/environments:batch", Kind.BATCH, "web-environment", "environment");

    /** What a generated route does; {@link #GENERIC} for the platform's own endpoints. */
    public enum Kind {
        GENERIC, START, PREVIEW, BATCH, LIST, GET
    }

    private final String operationId;
    private final HttpMethod method;
    private final String path;
    private final Kind kind;
    private final String workflow;
    private final String noun;

    PlatformOperation(String operationId, HttpMethod method, String path) {
        this(operationId, method, path, Kind.GENERIC, null, null);
    }

    PlatformOperation(String operationId, HttpMethod method, String path, Kind kind, String workflow, String noun) {
        this.operationId = operationId;
        this.method = method;
        this.path = path;
        this.kind = kind;
        this.workflow = workflow;
        this.noun = noun;
    }

    public String operationId() {
        return operationId;
    }

    public HttpMethod method() {
        return method;
    }

    /** The path template, with {@code {name}} parameters as the contract writes them. */
    public String path() {
        return path;
    }

    public Kind kind() {
        return kind;
    }

    /** The sample workflow behind a generated route, null for the platform's own endpoints and the generated reads. */
    public String workflow() {
        return workflow;
    }

    /** The noun of a generated route, null for the platform's own endpoints. */
    public String noun() {
        return noun;
    }

    public boolean generated() {
        return kind != Kind.GENERIC;
    }

    /** The names of the path parameters, in order. */
    public List<String> pathParameters() {
        List<String> names = new ArrayList<>();
        int from = 0;
        while (true) {
            int open = path.indexOf('{', from);
            if (open < 0) {
                return names;
            }
            int close = path.indexOf('}', open);
            names.add(path.substring(open + 1, close));
            from = close + 1;
        }
    }

    /** The operation with this id, or null. */
    public static PlatformOperation byId(String operationId) {
        for (PlatformOperation op : values()) {
            if (op.operationId.equals(operationId)) {
                return op;
            }
        }
        return null;
    }

    /** The generated route of a workflow and kind, or null. */
    public static PlatformOperation generated(String workflow, Kind kind) {
        for (PlatformOperation op : values()) {
            if (op.kind == kind && workflow.equals(op.workflow)) {
                return op;
            }
        }
        return null;
    }
}
