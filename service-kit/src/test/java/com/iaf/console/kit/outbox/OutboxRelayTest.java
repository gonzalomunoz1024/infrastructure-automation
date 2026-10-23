package com.iaf.console.kit.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Mono;

class OutboxRelayTest {

    private final InMemoryOutbox outbox = new InMemoryOutbox();
    private final InMemoryEventPublisher publisher = new InMemoryEventPublisher();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-23T10:00:00Z"), ZoneOffset.UTC);

    private void append(String... keys) {
        for (String key : keys) {
            outbox.append("console.jobs", "job-1", InMemoryOutboxTest.event(key)).block();
        }
    }

    @Test
    void publishesPendingEntriesInOrder() {
        append("k:1:1", "k:1:2", "k:1:3");
        OutboxRelay relay = new OutboxRelay(outbox, publisher, clock, "a", Duration.ofSeconds(30), 2);

        assertThat(relay.tick().block()).isEqualTo(3L);
        assertThat(publisher.published()).extracting(OutboxEntry::seq).containsExactly(1L, 2L, 3L);
        assertThat(outbox.pending(10).collectList().block()).isEmpty();
        assertThat(outbox.all().map(OutboxEntry::publishedAt).collectList().block()).containsOnly(clock.instant());
        assertThat(relay.tick().block()).isEqualTo(0L);
    }

    @Test
    void stopsAtTheFirstFailureAndRetriesIt() {
        append("k:1:1", "k:1:2", "k:1:3");
        Set<String> broken = new CopyOnWriteArraySet<>(List.of("k:1:2"));
        EventPublisherPort flaky = entry -> broken.contains(entry.key()) ? Mono.error(new IllegalStateException("broker away"))
                : publisher.publish(entry);
        OutboxRelay relay = new OutboxRelay(outbox, flaky, clock, "a", Duration.ofSeconds(30), 10);

        assertThat(relay.tick().block()).isEqualTo(1L);
        assertThat(publisher.published()).extracting(OutboxEntry::key).containsExactly("k:1:1");
        assertThat(outbox.all().filter(e -> e.key().equals("k:1:2")).blockFirst().attempts()).isEqualTo(1);

        broken.clear();
        assertThat(relay.tick().block()).isEqualTo(2L);
        assertThat(publisher.published()).extracting(OutboxEntry::key).containsExactly("k:1:1", "k:1:2", "k:1:3");
    }

    @Test
    void onlyTheLeaseHolderPublishes() {
        append("k:1:1");
        OutboxRelay a = new OutboxRelay(outbox, publisher, clock, "a", Duration.ofSeconds(30), 10);
        OutboxRelay b = new OutboxRelay(outbox, publisher, clock, "b", Duration.ofSeconds(30), 10);

        assertThat(a.tick().block()).isEqualTo(1L);
        append("k:1:2");
        assertThat(b.tick().block()).isEqualTo(0L);
        assertThat(outbox.pending(10).collectList().block()).hasSize(1);
        assertThat(a.tick().block()).isEqualTo(1L);

        a.stop();
        append("k:1:3");
        assertThat(b.tick().block()).isEqualTo(1L);
    }

    @Test
    void startsAndStopsASchedule() {
        OutboxRelay relay = new OutboxRelay(outbox, publisher, clock, "a", Duration.ofSeconds(30), 10);
        relay.start(Duration.ofMillis(20));
        assertThat(relay.running()).isTrue();
        append("k:1:1");
        Instant deadline = Instant.now().plusSeconds(5);
        while (publisher.published().isEmpty() && Instant.now().isBefore(deadline)) {
            Thread.onSpinWait();
        }
        assertThat(publisher.published()).hasSize(1);
        relay.stop();
        assertThat(relay.running()).isFalse();
    }
}
