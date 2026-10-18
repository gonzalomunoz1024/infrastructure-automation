package com.iaf.platform.audit.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.iaf.platform.audit.domain.EvidenceEntry;
import com.iaf.platform.audit.domain.EvidenceKind;
import com.iaf.platform.audit.domain.HashChain;
import com.iaf.platform.audit.ports.outbound.SiemPort;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class SiemExporterTest {

    static final Instant AT = Instant.parse("2026-10-17T10:00:00Z");

    static EvidenceEntry entry(int seq) {
        return EvidenceEntry.draft(AT.plusSeconds(seq), EvidenceKind.STEP, "ana", JsonNodeFactory.instance.objectNode().put("n", seq))
                .chained(seq, HashChain.GENESIS);
    }

    static SiemProperties props(int capacity, int attempts) {
        SiemProperties p = new SiemProperties();
        p.setQueueCapacity(capacity);
        p.setMaxAttempts(attempts);
        p.setBatchSize(10);
        p.setInitialBackoff(Duration.ofMillis(1));
        return p;
    }

    /** A SIEM that fails the first {@code failures} sends, recording what it took. */
    static final class FlakySiem implements SiemPort {
        final List<SiemEvent> taken = new CopyOnWriteArrayList<>();
        final AtomicInteger calls = new AtomicInteger();
        final int failures;

        FlakySiem(int failures) {
            this.failures = failures;
        }

        @Override
        public String name() {
            return "flaky";
        }

        @Override
        public void send(List<SiemEvent> batch) throws Exception {
            if (calls.incrementAndGet() <= failures) {
                throw new java.io.IOException("SIEM unavailable");
            }
            taken.addAll(batch);
        }
    }

    @Test
    void withoutASiemNothingIsQueued() {
        SiemExporter none = SiemExporter.none();

        assertThat(none.enabled()).isFalse();
        assertThat(none.offer("job_1", "vm-create", entry(1))).isFalse();
        assertThat(none.queued()).isZero();
    }

    @Test
    void retriesWithBackoffUntilTheSiemTakesTheBatch() {
        FlakySiem siem = new FlakySiem(2);
        SiemExporter exporter = new SiemExporter(List.of(siem), props(10, 5), null);

        exporter.offer("job_1", "vm-create", entry(1));
        exporter.offer("platform", null, entry(2));
        assertThat(exporter.flush(Duration.ofSeconds(5))).isTrue();

        assertThat(siem.calls.get()).isEqualTo(3);
        assertThat(siem.taken).extracting(SiemPort.SiemEvent::stream).containsExactly("job", "platform");
        assertThat(siem.taken.get(0).workflow()).isEqualTo("vm-create");
        assertThat(exporter.exported()).isEqualTo(2);
        assertThat(exporter.failed()).isZero();
    }

    @Test
    void givesABatchUpAfterTheLastAttemptAndCountsIt() {
        FlakySiem siem = new FlakySiem(Integer.MAX_VALUE);
        SiemExporter exporter = new SiemExporter(List.of(siem), props(10, 3), null);

        exporter.offer("job_1", "vm-create", entry(1));
        assertThat(exporter.flush(Duration.ofSeconds(5))).isTrue();

        assertThat(siem.calls.get()).isEqualTo(3);
        assertThat(exporter.failed()).isEqualTo(1);
        assertThat(exporter.exported()).isZero();
    }

    @Test
    void aFullQueueDropsTheNewestEntryAndCountsItInTheMetrics() {
        MeterRegistry registry = new SimpleMeterRegistry();
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("registry", registry);
        SiemExporter exporter = new SiemExporter(List.of(new FlakySiem(0)), props(2, 1), beans.getBeanProvider(MeterRegistry.class));

        assertThat(exporter.offer("job_1", "vm-create", entry(1))).isTrue();
        assertThat(exporter.offer("job_1", "vm-create", entry(2))).isTrue();
        assertThat(exporter.offer("job_1", "vm-create", entry(3))).isFalse();

        assertThat(exporter.dropped()).isEqualTo(1);
        assertThat(registry.get("audit.siem.dropped").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("audit.siem.queue").gauge().value()).isEqualTo(2.0);
        assertThat(exporter.flush(Duration.ofSeconds(5))).isTrue();
        assertThat(registry.get("audit.siem.exported").counter().count()).isEqualTo(2.0);
    }

    @Test
    void theWorkerSendsInTheBackgroundWithoutHoldingUpTheCaller() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        List<SiemPort.SiemEvent> taken = new CopyOnWriteArrayList<>();
        SiemPort slow = new SiemPort() {
            @Override
            public String name() {
                return "slow";
            }

            @Override
            public void send(List<SiemEvent> batch) throws Exception {
                release.await(5, TimeUnit.SECONDS);
                taken.addAll(batch);
            }
        };
        SiemExporter exporter = new SiemExporter(List.of(slow), props(10, 1), null);
        exporter.start();
        try {
            long started = System.nanoTime();
            exporter.offer("job_1", "vm-create", entry(1));
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
            assertThat(taken).isEmpty();

            release.countDown();
            assertThat(exporter.flush(Duration.ofSeconds(5))).isTrue();
            assertThat(taken).hasSize(1);
        } finally {
            exporter.stop();
        }
    }
}
