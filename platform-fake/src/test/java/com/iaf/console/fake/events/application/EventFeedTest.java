package com.iaf.console.fake.events.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.iaf.console.fake.common.FakeClock;
import com.iaf.console.fake.common.Ids;
import com.iaf.console.fake.identity.domain.Caller;
import com.iaf.console.fake.identity.domain.DevUsers;
import com.iaf.console.kit.events.EventEnvelope;

class EventFeedTest {

    private final FakeClock clock = new FakeClock(Clock.fixed(Instant.parse("2026-10-23T10:00:00Z"), ZoneOffset.UTC));
    private final EventFeed feed = new EventFeed(clock, Ids.seeded(clock.clock(), 7), 100);

    private EventEnvelope emit(String type, String accountId) {
        EventEnvelope.Context context = accountId == null ? null : new EventEnvelope.Context(accountId, "global", null, null);
        return feed.emit(type, type + ":" + feed.lastSeq(), clock.now(), "job", "job-1", "gonzalo", JsonNodeFactory.instance.objectNode(), context, null,
                null);
    }

    @Test
    void listsAfterASequenceWithTypePrefixes() {
        emit("job.started", null);
        emit("job.step", null);
        emit("resource.registered", null);
        Caller admin = DevUsers.byName("gonzalo");
        assertThat(feed.list(0, 10, List.of(), admin)).hasSize(3);
        assertThat(feed.list(1, 10, List.of("job."), admin)).extracting(EventEnvelope::type).containsExactly("job.step");
        assertThat(feed.list(0, 10, List.of("resource."), admin)).extracting(EventEnvelope::seq).containsExactly(3L);
    }

    @Test
    void consoleAccountsSeeTheirAccountOnly() {
        emit("job.started", "123456789012");
        emit("job.started", "210987654321");
        emit("job.started", null);
        Caller console = new Caller("sa-1", "console", null, List.of("team-compute"), List.of(), false, Caller.SERVICE, Set.of("events:read"),
                "123456789012", null);
        assertThat(feed.list(0, 10, List.of(), console)).extracting(EventEnvelope::seq).containsExactly(1L);
        assertThat(feed.list(0, 10, List.of(), DevUsers.byName("gonzalo"))).hasSize(3);
    }

    @Test
    void streamsReplayThenFollow() {
        emit("job.started", null);
        emit("job.step", null);
        List<EventEnvelope> seen = feed.stream(1, List.of(), DevUsers.byName("gonzalo"))
                .doOnSubscribe(s -> new Thread(() -> {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    emit("job.finished", null);
                }).start())
                .take(2)
                .collectList()
                .block(Duration.ofSeconds(5));
        assertThat(seen).extracting(EventEnvelope::seq).containsExactly(2L, 3L);
        assertThat(seen).extracting(EventEnvelope::type).containsExactly("job.step", "job.finished");
    }
}
