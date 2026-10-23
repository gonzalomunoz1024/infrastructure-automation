package com.iaf.console.kit.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.iaf.console.kit.events.EventEnvelope;

class InMemoryOutboxTest {

    private final InMemoryOutbox outbox = new InMemoryOutbox();

    static EventEnvelope event(String key) {
        return EventEnvelope.of("id-" + key, key, "job.started", Instant.parse("2026-10-23T10:00:00Z"), "job", "job-1", "gonzalo", null);
    }

    @Test
    void appendsInSequenceAndOncePerKey() {
        OutboxEntry a = outbox.append("console.jobs", "job-1", event("job.started:job-1:1")).block();
        OutboxEntry b = outbox.append("console.jobs", "job-1", event("job.step:job-1:2")).block();
        OutboxEntry again = outbox.append("console.jobs", "job-1", event("job.started:job-1:1").withId("other-id")).block();

        assertThat(a.seq()).isEqualTo(1);
        assertThat(b.seq()).isEqualTo(2);
        assertThat(again.seq()).isEqualTo(1);
        assertThat(again.event().id()).isEqualTo("id-job.started:job-1:1");
        assertThat(a.event().seq()).isEqualTo(1L);
        assertThat(outbox.highWater().block()).isEqualTo(2L);
        assertThat(outbox.pending(10).map(OutboxEntry::key).collectList().block()).containsExactly("job.started:job-1:1", "job.step:job-1:2");
        assertThat(outbox.after(1, 10).map(OutboxEntry::seq).collectList().block()).containsExactly(2L);
    }

    @Test
    void marksPublishedAndFailed() {
        outbox.append("t", "p", event("k:1:1")).block();
        outbox.append("t", "p", event("k:1:2")).block();
        outbox.markPublished(1, Instant.parse("2026-10-23T10:00:01Z")).block();
        outbox.markFailed(2).block();

        assertThat(outbox.pending(10).map(OutboxEntry::seq).collectList().block()).containsExactly(2L);
        assertThat(outbox.all().filter(e -> e.seq() == 1).blockFirst().publishedAt()).isEqualTo(Instant.parse("2026-10-23T10:00:01Z"));
        assertThat(outbox.all().filter(e -> e.seq() == 2).blockFirst().attempts()).isEqualTo(1);
    }

    @Test
    void theLeaseHasOneOwnerUntilItExpires() {
        Instant now = Instant.parse("2026-10-23T10:00:00Z");
        assertThat(outbox.acquireLease("a", Duration.ofSeconds(30), now).block()).isTrue();
        assertThat(outbox.acquireLease("b", Duration.ofSeconds(30), now.plusSeconds(10)).block()).isFalse();
        assertThat(outbox.acquireLease("a", Duration.ofSeconds(30), now.plusSeconds(10)).block()).isTrue();
        assertThat(outbox.acquireLease("b", Duration.ofSeconds(30), now.plusSeconds(41)).block()).isTrue();
        outbox.releaseLease("b").block();
        assertThat(outbox.acquireLease("a", Duration.ofSeconds(30), now.plusSeconds(42)).block()).isTrue();
    }
}
