package com.iaf.provider.sdk;

import java.time.Duration;
import java.util.Map;

/**
 * Where the worker finds Temporal and how long it lets calls in flight finish when it stops.
 *
 * @param address      the frontend address, {@code host:port}
 * @param namespace    the Temporal namespace the platform runs in
 * @param drainTimeout how long a stopping worker waits for the calls it is running
 */
public record WorkerSettings(String address, String namespace, Duration drainTimeout) {

    public static final String ADDRESS_ENV = "TEMPORAL_ADDRESS";
    public static final String NAMESPACE_ENV = "TEMPORAL_NAMESPACE";
    public static final String DRAIN_ENV = "WORKER_DRAIN_SECONDS";

    public static WorkerSettings defaults() {
        return new WorkerSettings("127.0.0.1:7233", "default", Duration.ofSeconds(30));
    }

    /** {@code TEMPORAL_ADDRESS}, {@code TEMPORAL_NAMESPACE} and {@code WORKER_DRAIN_SECONDS}, each falling back to the defaults. */
    public static WorkerSettings fromEnvironment() {
        return from(System.getenv());
    }

    static WorkerSettings from(Map<String, String> env) {
        WorkerSettings d = defaults();
        String drain = env.get(DRAIN_ENV);
        return new WorkerSettings(
                blank(env.get(ADDRESS_ENV)) ? d.address() : env.get(ADDRESS_ENV).trim(),
                blank(env.get(NAMESPACE_ENV)) ? d.namespace() : env.get(NAMESPACE_ENV).trim(),
                blank(drain) ? d.drainTimeout() : Duration.ofSeconds(Long.parseLong(drain.trim())));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
