package com.iaf.platform.runs.adapters.outbound.temporal;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import com.iaf.platform.config.PlatformProperties;

import io.temporal.worker.WorkerFactory;
import lombok.extern.slf4j.Slf4j;

/**
 * The life of the in-process worker. Polling starts once the application is ready, so the API
 * and the seeder are up first; {@code WorkerFactory.start} asks the server about the namespace,
 * and while that fails the start is retried in the background (2 s, doubling up to 30 s), so the
 * application comes up without a reachable server and jobs wait in the task queue meanwhile.
 * With the context the worker stops gracefully: it stops polling and lets the activities in
 * flight finish (they still need the slice's beans, which outlive this one) for up to
 * {@link #DRAIN}; an unreachable server at that point is logged, not thrown.
 */
@Slf4j
public class InterpreterWorkerLifecycle implements DisposableBean {

    static final Duration FIRST_RETRY = Duration.ofSeconds(2);
    static final Duration MAX_RETRY = Duration.ofSeconds(30);
    static final Duration DRAIN = Duration.ofSeconds(20);

    private final WorkerFactory factory;
    private final PlatformProperties.Temporal temporal;
    private final ScheduledExecutorService retries = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "temporal-worker-start");
        t.setDaemon(true);
        return t;
    });
    private Duration nextRetry = FIRST_RETRY;
    private boolean closed;

    public InterpreterWorkerLifecycle(WorkerFactory factory, PlatformProperties.Temporal temporal) {
        this.factory = factory;
        this.temporal = temporal;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void onReady() {
        start();
    }

    /** One attempt to start polling; schedules the next one when the server cannot be reached. True once polling. */
    synchronized boolean start() {
        if (closed) {
            return false;
        }
        if (factory.isStarted()) {
            return true;
        }
        try {
            factory.start();
            log.info("Temporal worker polling task queue {} on {} (namespace {})", temporal.getTaskQueue(), temporal.getTarget(), temporal.getNamespace());
            return true;
        } catch (RuntimeException e) {
            log.warn("Temporal at {} is not reachable yet ({}); jobs wait in task queue {}, next try in {} s", temporal.getTarget(), e.toString(),
                    temporal.getTaskQueue(), nextRetry.toSeconds());
            retries.schedule(this::start, nextRetry.toMillis(), TimeUnit.MILLISECONDS);
            nextRetry = nextRetry.multipliedBy(2).compareTo(MAX_RETRY) > 0 ? MAX_RETRY : nextRetry.multipliedBy(2);
            return false;
        }
    }

    synchronized boolean polling() {
        return !closed && factory.isStarted();
    }

    @Override
    public void destroy() {
        boolean started;
        synchronized (this) {
            closed = true;
            retries.shutdownNow();
            started = factory.isStarted();
        }
        if (!started) {
            return;
        }
        try {
            factory.shutdown();
            factory.awaitTermination(DRAIN.toMillis(), TimeUnit.MILLISECONDS);
            log.info("Temporal worker on task queue {} stopped", temporal.getTaskQueue());
        } catch (RuntimeException e) {
            log.warn("Temporal worker on task queue {} stopped without reaching {}: {}", temporal.getTaskQueue(), temporal.getTarget(), e.toString());
        }
    }
}
