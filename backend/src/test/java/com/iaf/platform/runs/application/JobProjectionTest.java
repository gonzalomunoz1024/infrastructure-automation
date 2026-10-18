package com.iaf.platform.runs.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.events.DomainEvent;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.runs.FlakyProjectionStore;
import com.iaf.platform.runs.adapters.outbound.InMemoryProjectionStore;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.runs.domain.JobEvent;
import com.iaf.platform.runs.domain.JobStatus;
import com.iaf.platform.runs.ports.outbound.ProjectionStore;

/** The read model's folding: a store failure reaches the publisher. */
class JobProjectionTest {

    private static final Instant T0 = Instant.parse("2026-10-01T09:00:00Z");

    private final MovingClock clock = new MovingClock(T0);
    private final EventBus bus = new EventBus(clock);
    private final List<DomainEvent> seen = new CopyOnWriteArrayList<>();

    private record Wiring(JobProjection projection, JobEvents events) {
    }

    private Wiring wiring(ProjectionStore store) {
        JobProjection projection = new JobProjection(bus, store);
        projection.register();
        bus.register(seen::add);
        return new Wiring(projection, new JobEvents(bus, projection, clock));
    }

    private static Job running(String id) {
        return Job.builder().id(id).workflow("vm-restart").version("1.2.0").title("Restart a VM").status(JobStatus.RUNNING)
                .requester(Identity.SYSTEM).startedAt(T0).input(JsonNodeFactory.instance.objectNode()).build();
    }

    private static void finish(JobEvents events, String jobId) {
        events.finished(jobId, "vm-restart", "1.2.0", JobStatus.SUCCEEDED, 1200, null, null, null, null, T0.plusSeconds(2));
    }

    private static long count(List<JobEvent> history, String event) {
        return history.stream().filter(e -> event.equals(e.data().path("event").asText())).count();
    }

    @Test
    void aStoreThatRefusesTheFinalEventFailsThePublisherAndKeepsNothingUntilItTakesIt() {
        FlakyProjectionStore store = FlakyProjectionStore.refusingTerminalSaves(1);
        Wiring w = wiring(store);
        w.projection().put(running("job_1"));

        assertThatThrownBy(() -> finish(w.events(), "job_1")).isInstanceOf(IllegalStateException.class).hasMessageContaining("store is down");
        assertThat(w.projection().get("job_1").orElseThrow().status()).isEqualTo(JobStatus.RUNNING);
        assertThat(count(w.projection().history("job_1"), "job.finished")).isZero();
        assertThat(seen).as("nobody heard of an event the read model could not keep").isEmpty();

        finish(w.events(), "job_1"); // the retry: the store is back

        assertThat(w.projection().get("job_1").orElseThrow().status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(count(w.projection().history("job_1"), "job.finished")).isOne();
        assertThat(seen).extracting(DomainEvent::type).containsExactly("job.finished");
    }

    @Test
    void anEventWhoseHistoryAppendFailsIsAppliedAgainOnTheRetryAndKeptOnce() {
        FlakyProjectionStore store = new FlakyProjectionStore(job -> false, e -> "job.finished".equals(e.data().path("event").asText()), 2);
        Wiring w = wiring(store);
        w.projection().put(running("job_1"));

        assertThatThrownBy(() -> finish(w.events(), "job_1")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> finish(w.events(), "job_1")).isInstanceOf(IllegalStateException.class);
        finish(w.events(), "job_1");

        List<JobEvent> history = w.projection().history("job_1");
        assertThat(count(history, "job.finished")).isOne();
        assertThat(history).extracting(JobEvent::seq).containsExactly(1L);
        assertThat(w.projection().get("job_1").orElseThrow().status()).isEqualTo(JobStatus.SUCCEEDED);
    }

    @Test
    void anEventFoldedBeforeItsPublicationIsNotFoldedAgainByTheBusListener() {
        Wiring w = wiring(new InMemoryProjectionStore());
        w.projection().put(running("job_1"));

        w.events().log("job_1", "hello");
        bus.publish("job.log", JobEvents.AGGREGATE, "job_1", "platform", JsonNodeFactory.instance.objectNode().put("message", "straight on the bus"));

        assertThat(w.projection().history("job_1")).extracting(e -> e.data().path("message").asText()).containsExactly("hello", "straight on the bus");
    }

    @Test
    void aStoreFailureOnTheBusIsLoggedByTheBusAndTheEventIsNotKept() {
        FlakyProjectionStore store = new FlakyProjectionStore(job -> false, e -> true, 1);
        Wiring w = wiring(store);
        w.projection().put(running("job_1"));

        bus.publish("job.log", JobEvents.AGGREGATE, "job_1", "platform", JsonNodeFactory.instance.objectNode().put("message", "lost"));

        assertThat(w.projection().history("job_1")).isEmpty();
        assertThat(store.refused()).isOne();
    }

    /** A clock the test moves forward. */
    static final class MovingClock extends Clock {
        private volatile Instant now;

        MovingClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
