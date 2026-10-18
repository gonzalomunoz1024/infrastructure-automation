package com.iaf.platform.audit.application;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.iaf.platform.audit.domain.EvidenceEntry;
import com.iaf.platform.audit.domain.EvidenceRecord;
import com.iaf.platform.audit.ports.outbound.SiemPort;
import com.iaf.platform.audit.ports.outbound.SiemPort.SiemEvent;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Sends every evidence entry to the SIEM adapters (profile {@code siem}) without ever slowing the
 * evidence down: {@link #offer} only puts the entry on a bounded queue, and one worker thread sends
 * batches, retrying each SIEM with exponential backoff before giving the batch up. A full queue
 * drops the newest entry and counts it ({@code audit.siem.dropped}); the evidence store, not the
 * SIEM, is the record. With no SIEM adapter wired nothing is queued at all.
 */
@Slf4j
@Component
public class SiemExporter {

    private final List<SiemPort> ports;
    private final SiemProperties props;
    private final BlockingQueue<SiemEvent> queue;
    private final AtomicLong exported = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final Counter exportedCounter;
    private final Counter droppedCounter;
    private final Counter failedCounter;
    private volatile Thread worker;
    private volatile boolean running;

    public SiemExporter(List<SiemPort> ports, SiemProperties props, ObjectProvider<MeterRegistry> meters) {
        this.ports = ports == null ? List.of() : List.copyOf(ports);
        this.props = props == null ? new SiemProperties() : props;
        this.queue = new ArrayBlockingQueue<>(Math.max(1, this.props.getQueueCapacity()));
        MeterRegistry registry = meters == null ? null : meters.getIfAvailable();
        if (registry != null && !this.ports.isEmpty()) {
            exportedCounter = Counter.builder("audit.siem.exported").description("Evidence entries delivered to the SIEM").register(registry);
            droppedCounter = Counter.builder("audit.siem.dropped").description("Evidence entries dropped on a full SIEM queue").register(registry);
            failedCounter = Counter.builder("audit.siem.failed").description("Evidence entries given up after every retry").register(registry);
            Gauge.builder("audit.siem.queue", queue, BlockingQueue::size).description("Evidence entries waiting for the SIEM").register(registry);
        } else {
            exportedCounter = null;
            droppedCounter = null;
            failedCounter = null;
        }
    }

    /** An exporter that sends nothing (no SIEM wired). */
    public static SiemExporter none() {
        return new SiemExporter(List.of(), new SiemProperties(), null);
    }

    public boolean enabled() {
        return !ports.isEmpty();
    }

    @PostConstruct
    public void start() {
        if (ports.isEmpty() || worker != null) {
            return;
        }
        running = true;
        Thread t = new Thread(this::loop, "siem-export");
        t.setDaemon(true);
        worker = t;
        t.start();
        log.info("SIEM export on: {}", ports.stream().map(SiemPort::name).toList());
    }

    @PreDestroy
    public void stop() {
        running = false;
        Thread t = worker;
        if (t != null) {
            t.interrupt();
            try {
                t.join(Duration.ofSeconds(5).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        worker = null;
    }

    /** Queue one chained (and already redacted) entry; false when nothing is wired or the queue is full. */
    public boolean offer(String recordId, String workflow, EvidenceEntry entry) {
        if (ports.isEmpty() || entry == null) {
            return false;
        }
        SiemEvent event = new SiemEvent(streamOf(recordId), recordId, workflow, entry.seq(), entry.at(),
                entry.kind() == null ? null : entry.kind().json(), entry.actor(), entry.data(), entry.hash(), entry.previous());
        if (queue.offer(event)) {
            return true;
        }
        long n = dropped.incrementAndGet();
        if (droppedCounter != null) {
            droppedCounter.increment();
        }
        if (n == 1 || n % 1000 == 0) {
            log.warn("SIEM queue full ({} entries): {} evidence entries dropped so far (the evidence store keeps them)", queue.size(), n);
        }
        return false;
    }

    /** Sends at most one batch now, on the calling thread; the number of entries taken off the queue. */
    int drainOnce() {
        List<SiemEvent> batch = new ArrayList<>();
        queue.drainTo(batch, Math.max(1, props.getBatchSize()));
        if (!batch.isEmpty()) {
            deliver(batch);
        }
        return batch.size();
    }

    /** Waits until everything queued so far was sent or given up; false on timeout. */
    public boolean flush(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (queue.isEmpty() && inFlight.get() == 0) {
                return true;
            }
            if (worker == null) {
                drainOnce();
                continue;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return queue.isEmpty() && inFlight.get() == 0;
    }

    public long exported() {
        return exported.get();
    }

    public long dropped() {
        return dropped.get();
    }

    public long failed() {
        return failed.get();
    }

    public int queued() {
        return queue.size();
    }

    private void loop() {
        while (running) {
            try {
                SiemEvent first = queue.poll(1, TimeUnit.SECONDS);
                if (first == null) {
                    continue;
                }
                inFlight.incrementAndGet();
                try {
                    List<SiemEvent> batch = new ArrayList<>();
                    batch.add(first);
                    queue.drainTo(batch, Math.max(1, props.getBatchSize()) - 1);
                    deliver(batch);
                } finally {
                    inFlight.decrementAndGet();
                }
            } catch (InterruptedException e) {
                if (!running) {
                    return;
                }
            } catch (RuntimeException e) {
                log.warn("SIEM export loop: {}", e.toString());
            }
        }
    }

    private void deliver(List<SiemEvent> batch) {
        for (SiemPort port : ports) {
            int attempts = Math.max(1, props.getMaxAttempts());
            Duration backoff = props.getInitialBackoff() == null ? Duration.ofSeconds(1) : props.getInitialBackoff();
            for (int attempt = 1; attempt <= attempts; attempt++) {
                try {
                    port.send(batch);
                    exported.addAndGet(batch.size());
                    if (exportedCounter != null) {
                        exportedCounter.increment(batch.size());
                    }
                    break;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    giveUp(port, batch, attempt, e);
                    return;
                } catch (Exception e) {
                    if (attempt == attempts) {
                        giveUp(port, batch, attempt, e);
                        break;
                    }
                    log.debug("SIEM {} attempt {} failed: {}", port.name(), attempt, e.toString());
                    if (!sleep(backoff.multipliedBy(1L << Math.min(attempt - 1, 16)))) {
                        giveUp(port, batch, attempt, e);
                        return;
                    }
                }
            }
        }
    }

    private void giveUp(SiemPort port, List<SiemEvent> batch, int attempts, Exception e) {
        failed.addAndGet(batch.size());
        if (failedCounter != null) {
            failedCounter.increment(batch.size());
        }
        log.warn("SIEM {} did not take {} evidence entries after {} attempts: {}", port.name(), batch.size(), attempts, e.toString());
    }

    private static boolean sleep(Duration d) {
        try {
            Thread.sleep(Math.max(0, d.toMillis()));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    static String streamOf(String recordId) {
        if (EvidenceRecord.PLATFORM.equals(recordId)) {
            return "platform";
        }
        return recordId != null && recordId.startsWith(EvidenceRecord.RELEASE_PREFIX) ? "release" : "job";
    }
}
