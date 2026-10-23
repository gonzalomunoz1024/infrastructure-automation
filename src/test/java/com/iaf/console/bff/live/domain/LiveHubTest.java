package com.iaf.console.bff.live.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import reactor.test.StepVerifier;

class LiveHubTest {

    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");

    @Test
    void replayIsPerAccountPlusBroadcastsInOrder() {
        LiveHub hub = new LiveHub(100, () -> NOW);
        hub.publish("a", event("job.updated", "1"));
        hub.publish("b", event("job.updated", "2"));
        hub.broadcast(event(LiveEvent.TYPES_CHANGED, "3"));
        hub.publish("a", event("resource.registered", "4"));
        assertEquals(4, hub.current());
        List<Sequenced> forA = hub.subscribe("a", 0L).take(3).collectList().block(Duration.ofSeconds(2));
        assertEquals(List.of(1L, 3L, 4L), forA.stream().map(Sequenced::seq).toList());
        assertEquals(List.of("1", "3", "4"), forA.stream().map(s -> s.event().key()).toList());
        List<Sequenced> forB = hub.subscribe("b", 0L).take(2).collectList().block(Duration.ofSeconds(2));
        assertEquals(List.of(2L, 3L), forB.stream().map(Sequenced::seq).toList());
        List<Sequenced> after = hub.subscribe("a", 3L).take(1).collectList().block(Duration.ofSeconds(2));
        assertEquals(4L, after.get(0).seq());
    }

    @Test
    void liveEventsFollowTheReplay() {
        LiveHub hub = new LiveHub(100, () -> NOW);
        hub.publish("a", event("job.updated", "1"));
        StepVerifier.create(hub.subscribe("a", 0L).take(3))
                .assertNext(s -> assertEquals(1L, s.seq()))
                .then(() -> {
                    hub.publish("b", event("job.updated", "x"));
                    hub.publish("a", event("job.updated", "2"));
                    hub.broadcast(event(LiveEvent.REGION_CHANGED, "3"));
                })
                .assertNext(s -> assertEquals("2", s.event().key()))
                .assertNext(s -> {
                    assertEquals("3", s.event().key());
                    assertTrue(s.global());
                })
                .verifyComplete();
    }

    @Test
    void aLiveOnlySubscriberSeesNothingOld() {
        LiveHub hub = new LiveHub(100, () -> NOW);
        hub.publish("a", event("job.updated", "old"));
        StepVerifier.create(hub.subscribe("a", null).take(1))
                .then(() -> hub.publish("a", event("job.updated", "new")))
                .assertNext(s -> assertEquals("new", s.event().key()))
                .verifyComplete();
    }

    @Test
    void aCursorOlderThanTheRingGetsAResyncFirst() {
        LiveHub hub = new LiveHub(2, () -> NOW);
        for (int i = 1; i <= 5; i++) {
            hub.publish("a", event("job.updated", String.valueOf(i)));
        }
        List<Sequenced> replay = hub.subscribe("a", 1L).take(3).collectList().block(Duration.ofSeconds(2));
        assertEquals(LiveEvent.RESYNC, replay.get(0).event().type());
        assertEquals(NOW, replay.get(0).event().at());
        assertEquals(List.of("4", "5"), replay.subList(1, 3).stream().map(s -> s.event().key()).toList());
        List<Sequenced> recent = hub.subscribe("a", 4L).take(1).collectList().block(Duration.ofSeconds(2));
        assertEquals("5", recent.get(0).event().key(), "a cursor inside the ring needs no resync");
    }

    private static LiveEvent event(String type, String key) {
        return new LiveEvent(type, null, NOW, null, key, null);
    }
}
