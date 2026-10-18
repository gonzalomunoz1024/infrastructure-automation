package com.iaf.platform.runs.adapters.outbound.temporal;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;

/**
 * Keeps an activity alive while its blocking call runs: the call runs on a virtual thread and the
 * activity's own thread heartbeats every {@code interval} until the call returns. When Temporal
 * answers a heartbeat with a cancel, or the activity already timed out, the call is interrupted
 * and that answer is rethrown, so the worker stops working for a step that has moved on.
 */
final class Heartbeats {

    private static final ExecutorService CALLS = Executors.newVirtualThreadPerTaskExecutor();

    private Heartbeats() {
    }

    /** Run {@code body}, heartbeating while it runs; a null or non-positive interval runs it as is. */
    static <T> T during(Duration interval, Supplier<T> body) {
        if (interval == null || !interval.isPositive()) {
            return body.get();
        }
        ActivityExecutionContext activity = Activity.getExecutionContext();
        Future<T> call = CALLS.submit(body::get);
        try {
            while (true) {
                try {
                    return call.get(interval.toMillis(), TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    activity.heartbeat(null);
                }
            }
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException r) {
                throw r;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while the step's call ran", e);
        } finally {
            call.cancel(true);
        }
    }
}
