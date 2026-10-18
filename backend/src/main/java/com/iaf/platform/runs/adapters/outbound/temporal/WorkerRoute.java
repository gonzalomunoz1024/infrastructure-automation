package com.iaf.platform.runs.adapters.outbound.temporal;

import java.time.Duration;

/**
 * Where a step's call runs when a team's own worker serves it (an sdk provider's action, a
 * {@code call: activity}): the task queue the worker listens on and how long it may go without a
 * heartbeat (null = no heartbeat timeout).
 */
public record WorkerRoute(String taskQueue, Duration heartbeatTimeout) {
}
