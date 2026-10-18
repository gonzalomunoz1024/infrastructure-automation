package com.iaf.provider.sdk;

import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(WorkerSettings.class);

    public static WorkerSettings defaults() {
        return new WorkerSettings("127.0.0.1:7233", "default", Duration.ofSeconds(30));
    }

    /**
     * {@code TEMPORAL_ADDRESS}, {@code TEMPORAL_NAMESPACE} and {@code WORKER_DRAIN_SECONDS}, each
     * falling back to the defaults; a drain time that is not a whole number of seconds is logged
     * and falls back too, as in the other SDKs.
     */
    public static WorkerSettings fromEnvironment() {
        return from(System.getenv());
    }

    static WorkerSettings from(Map<String, String> env) {
        WorkerSettings d = defaults();
        return new WorkerSettings(
                blank(env.get(ADDRESS_ENV)) ? d.address() : env.get(ADDRESS_ENV).trim(),
                blank(env.get(NAMESPACE_ENV)) ? d.namespace() : env.get(NAMESPACE_ENV).trim(),
                drain(env.get(DRAIN_ENV), d.drainTimeout()));
    }

    private static Duration drain(String value, Duration fallback) {
        if (blank(value)) {
            return fallback;
        }
        try {
            long seconds = Long.parseLong(value.trim());
            if (seconds >= 0) {
                return Duration.ofSeconds(seconds);
            }
        } catch (NumberFormatException e) {
            // logged below
        }
        log.warn("{} is a whole number of seconds, not '{}'; the worker drains for {}s", DRAIN_ENV, value.trim(), fallback.toSeconds());
        return fallback;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
