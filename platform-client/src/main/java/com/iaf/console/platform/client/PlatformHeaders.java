package com.iaf.console.platform.client;

/** The header names of the target contract. */
public final class PlatformHeaders {

    public static final String ON_BEHALF_OF = "X-On-Behalf-Of";
    public static final String RESOURCE_WFRN = "X-Resource-Wfrn";
    public static final String CONSOLE_REQUEST_ID = "X-Console-Request-Id";
    public static final String JOB_LABEL_PREFIX = "X-Job-Label-";
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    public static final String IDEMPOTENT_REPLAY = "Idempotent-Replay";
    public static final String TRACEPARENT = "traceparent";
    public static final String WORKFLOW_VERSION = "X-Workflow-Version";
    public static final String LAST_EVENT_ID = "Last-Event-ID";
    public static final String IF_NONE_MATCH = "If-None-Match";
    public static final String ETAG = "ETag";
    public static final String LINK = "Link";
    public static final String RETRY_AFTER = "Retry-After";
    public static final String DEV_USER = "X-User";
    public static final String CORRELATION_ID = "X-Correlation-Id";

    /** The most labels a job takes. */
    public static final int MAX_LABELS = 16;
    /** The shape of a label name. */
    public static final String LABEL_NAME = "[a-z0-9-]{1,32}";
    /** The longest label value. */
    public static final int MAX_LABEL_VALUE = 128;
    /** The longest idempotency key. */
    public static final int MAX_IDEMPOTENCY_KEY = 255;

    private PlatformHeaders() {
    }
}
