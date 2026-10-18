package com.iaf.platform.runs.adapters.outbound.temporal;

import java.time.Duration;

/**
 * Where a step's call runs when a team's own worker serves it (an sdk provider's action, a
 * {@code call: activity}): the task queue the worker listens on, how long it may go without a
 * heartbeat (null = no heartbeat timeout) and the provider version that serves it (null for a
 * {@code call: activity}, and in a route recorded before it was kept), which its telemetry names.
 */
public record WorkerRoute(String taskQueue, Duration heartbeatTimeout, String providerVersion) {

    /** A route whose provider version is not known. */
    public WorkerRoute(String taskQueue, Duration heartbeatTimeout) {
        this(taskQueue, heartbeatTimeout, null);
    }
}
