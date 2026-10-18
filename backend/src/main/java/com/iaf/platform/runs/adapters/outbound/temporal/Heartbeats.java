package com.iaf.platform.runs.adapters.outbound.temporal;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.client.ActivityCompletionException;

/**
 * Keeps an activity alive while its blocking call runs: the call runs on a virtual thread and the
 * activity's own thread heartbeats every {@code interval} until the call returns. When Temporal
 * answers a heartbeat with a cancel (the workflow cancelled the call's scope), or the activity is
 * gone or timed out, {@code onStop} runs (the call's engine then reads as cancelled, so its
 * runner ends its process), the call gets {@link #STOP_GRACE} to end by itself, whatever is left
 * is interrupted, and Temporal's answer is rethrown: the worker stops working for a step that has
 * moved on.
 */
final class Heartbeats {

    /** How long a call that heard of a cancel gets to end by itself (a runner stops its process) before it is interrupted. */
    static final Duration STOP_GRACE = Duration.ofSeconds(10);

    private static final ExecutorService CALLS = Executors.newVirtualThreadPerTaskExecutor();

    private Heartbeats() {
    }

    /** Run {@code body}, heartbeating while it runs; a null or non-positive interval runs it as is, and nothing cancels it. */
    static <T> T during(Duration interval, Runnable onStop, Supplier<T> body) {
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
                    heartbeat(activity, onStop, call);
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

    private static void heartbeat(ActivityExecutionContext activity, Runnable onStop, Future<?> call) {
        try {
            activity.heartbeat(null);
        } catch (ActivityCompletionException stop) {
            onStop.run();
            try {
                call.get(STOP_GRACE.toMillis(), TimeUnit.MILLISECONDS);
            } catch (ExecutionException | TimeoutException | CancellationException ended) {
                // how the call ended no longer matters: the answer is Temporal's
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw stop;
        }
    }
}
