package com.iaf.console.fake.events.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.iaf.console.kit.events.EventEnvelope;

class EventRingTest {

    private static EventEnvelope event(int n) {
        return EventEnvelope.of("evt-" + n, "job.step:job-1:" + n, "job.step", Instant.EPOCH.plusSeconds(n), "job", "job-1", "gonzalo",
                JsonNodeFactory.instance.objectNode().put("n", n));
    }

    @Test
    void numbersEventsFromOneAndAnswersPagesAfterASequence() {
        EventRing ring = new EventRing(10);
        for (int i = 1; i <= 5; i++) {
            assertThat(ring.append(event(i)).seq()).isEqualTo(i);
        }
        assertThat(ring.firstSeq()).isEqualTo(1);
        assertThat(ring.lastSeq()).isEqualTo(5);
        List<EventEnvelope> page = ring.after(2, 2);
        assertThat(page).extracting(EventEnvelope::seq).containsExactly(3L, 4L);
        assertThat(ring.after(5, 10)).isEmpty();
    }

    @Test
    void dropsTheOldestEventsWhenFull() {
        EventRing ring = new EventRing(3);
        for (int i = 1; i <= 5; i++) {
            ring.append(event(i));
        }
        assertThat(ring.size()).isEqualTo(3);
        assertThat(ring.firstSeq()).isEqualTo(3);
        assertThat(ring.after(0, 10)).extracting(EventEnvelope::seq).containsExactly(3L, 4L, 5L);
    }
}
