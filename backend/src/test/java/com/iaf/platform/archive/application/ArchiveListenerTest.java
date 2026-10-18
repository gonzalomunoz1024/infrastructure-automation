package com.iaf.platform.archive.application;

import static com.iaf.platform.archive.ArchiveFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.iaf.platform.archive.ports.inbound.ArchiveCommands;
import com.iaf.platform.archive.ports.inbound.ArchiveCommands.Outcome;
import com.iaf.platform.common.events.EventBus;

import reactor.core.publisher.Mono;

class ArchiveListenerTest {

    EventBus bus;
    ArchiveCommands archive;
    ArchiveListener listener;

    @BeforeEach
    void setUp() {
        bus = new EventBus(Clock.fixed(Instant.parse("2026-10-18T10:00:00Z"), ZoneOffset.UTC));
        archive = mock(ArchiveCommands.class);
        when(archive.archive(anyString())).thenReturn(Mono.just(Outcome.ARCHIVED));
        listener = new ArchiveListener(bus, archive);
        listener.subscribe();
    }

    @AfterEach
    void tearDown() {
        listener.stop();
    }

    @Test
    void aJobIsArchivedWhenItFinishesOrIsCancelled() {
        bus.publish("job.finished", "job", "j1", "system", json("{\"jobId\":\"j1\",\"status\":\"succeeded\"}"));
        bus.publish("job.finished", "job", "j2", "system", json("{\"jobId\":\"j2\",\"status\":\"rolled-back\"}"));
        bus.publish("job.cancelled", "job", "j3", "ana", json("{\"jobId\":\"j3\",\"by\":\"ana\"}"));

        verify(archive, timeout(2000)).archive("j1");
        verify(archive, timeout(2000)).archive("j2");
        verify(archive, timeout(2000)).archive("j3");
    }

    @Test
    void jobsAreArchivedOneAtATimeInTheOrderTheyEnded() {
        List<String> order = new CopyOnWriteArrayList<>();
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();
        when(archive.archive(anyString())).thenAnswer(call -> Mono.defer(() -> {
            order.add(call.getArgument(0));
            most.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            return Mono.just(Outcome.ARCHIVED).delayElement(Duration.ofMillis(50));
        }).doOnTerminate(inFlight::decrementAndGet));

        bus.publish("job.finished", "job", "b1", "system", json("{\"jobId\":\"b1\",\"status\":\"failed\"}"));
        bus.publish("job.cancelled", "job", "j2", "system", json("{\"jobId\":\"j2\"}"));
        bus.publish("job.finished", "job", "b1", "system", json("{\"jobId\":\"b1\",\"status\":\"succeeded\"}"));

        verify(archive, timeout(2000).times(2)).archive("b1");
        assertThat(order).containsExactly("b1", "j2", "b1");
        assertThat(most.get()).as("archives at once").isEqualTo(1);
    }

    @Test
    void nothingIsArchivedBeforeTheJobEnds() {
        for (String type : List.of("job.started", "job.step", "job.log", "job.approval-requested", "job.attention", "job.changed")) {
            bus.publish(type, "job", "j1", "system", json("{\"jobId\":\"j1\"}"));
        }
        bus.publish("job.finished", "release", "r1", "system", json("{}"));

        verify(archive, after(300).never()).archive(anyString());
    }

    @Test
    void aFailedArchiveNeverReachesThePublisherAndTheNextJobIsStillArchived() {
        when(archive.archive("j1")).thenReturn(Mono.error(new IllegalStateException("bucket unreachable")));
        when(archive.archive("j2")).thenThrow(new IllegalStateException("thrown, not signalled"));

        assertThatCode(() -> {
            bus.publish("job.finished", "job", "j1", "system", json("{\"jobId\":\"j1\",\"status\":\"failed\"}"));
            bus.publish("job.finished", "job", "j2", "system", json("{\"jobId\":\"j2\",\"status\":\"failed\"}"));
        }).doesNotThrowAnyException();
        bus.publish("job.cancelled", "job", "j3", "system", json("{\"jobId\":\"j3\"}"));

        verify(archive, timeout(2000)).archive("j3");
    }

    @Test
    void theStartArchivesWhatIsMissingAndAFailedSweepIsOnlyLogged() {
        AtomicBoolean swept = new AtomicBoolean();
        when(archive.archiveMissing()).thenReturn(Mono.fromCallable(() -> {
            swept.set(true);
            return 3L;
        }));

        listener.archiveMissing();

        verify(archive).archiveMissing();
        assertThat(swept).as("the sweep was subscribed to").isTrue();

        when(archive.archiveMissing()).thenReturn(Mono.error(new IllegalStateException("projection unavailable")));
        assertThatCode(listener::archiveMissing).doesNotThrowAnyException();
    }

    @Test
    void afterStoppingNoJobIsQueued() {
        listener.stop();

        assertThatCode(() -> bus.publish("job.finished", "job", "j9", "system", json("{\"jobId\":\"j9\"}"))).doesNotThrowAnyException();

        verify(archive, after(300).never()).archive("j9");
        verify(archive, never()).archiveMissing();
    }
}
