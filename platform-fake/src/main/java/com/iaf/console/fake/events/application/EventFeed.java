package com.iaf.console.fake.events.application;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.fake.common.FakeClock;
import com.iaf.console.fake.common.Ids;
import com.iaf.console.fake.events.domain.EventRing;
import com.iaf.console.fake.identity.domain.Caller;
import com.iaf.console.kit.events.EventEnvelope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * The durable event feed (P-01) over the in-memory ring: publishing assigns the sequence and a
 * random id, reads page by sequence, and streams replay the ring and then follow it live.
 */
public final class EventFeed {

    /** How often a stream looks for new events when no publish woke it. */
    public static final Duration POLL = Duration.ofMillis(200);

    private final EventRing ring;
    private final FakeClock clock;
    private final Ids ids;
    private final Sinks.Many<Long> wake = Sinks.many().multicast().directBestEffort();

    public EventFeed(FakeClock clock, Ids ids, int capacity) {
        this.ring = new EventRing(capacity);
        this.clock = clock;
        this.ids = ids;
    }

    public EventFeed(FakeClock clock, Ids ids) {
        this(clock, ids, EventRing.DEFAULT_CAPACITY);
    }

    /** Stores an event and wakes the streams; the stored copy carries its sequence and an id. */
    public EventEnvelope publish(EventEnvelope event) {
        EventEnvelope withId = event.id() == null ? event.withId(ids.event()) : event;
        EventEnvelope stored = ring.append(withId);
        synchronized (wake) {
            wake.tryEmitNext(stored.seq());
        }
        return stored;
    }

    /** Builds and publishes an envelope: the id, sequence and version are the feed's. */
    public EventEnvelope emit(String type, String key, Instant at, String aggregate, String aggregateId, String actor, JsonNode data,
            EventEnvelope.Context context, EventEnvelope.OnBehalfOf onBehalfOf, String traceparent) {
        EventEnvelope event = EventEnvelope.of(null, key, type, at == null ? clock.now() : at, aggregate, aggregateId, actor, data);
        if (context != null || onBehalfOf != null) {
            event = event.withContext(context, onBehalfOf);
        }
        if (traceparent != null) {
            event = event.withTraceparent(traceparent);
        }
        return publish(event);
    }

    public long lastSeq() {
        return ring.lastSeq();
    }

    public long firstSeq() {
        return ring.firstSeq();
    }

    /** The sequences of published events as they are stored, for anything that follows the feed. */
    public Flux<Long> wakeups() {
        return wake.asFlux();
    }

    /** One page after a sequence, visible to the caller and matching the type prefixes. */
    public List<EventEnvelope> list(long after, int limit, List<String> typePrefixes, Caller caller) {
        Predicate<EventEnvelope> filter = filter(typePrefixes, caller);
        List<EventEnvelope> out = new ArrayList<>();
        long cursor = after;
        while (out.size() < limit) {
            List<EventEnvelope> batch = ring.after(cursor, 1000);
            if (batch.isEmpty()) {
                break;
            }
            for (EventEnvelope event : batch) {
                cursor = event.seq();
                if (filter.test(event)) {
                    out.add(event);
                    if (out.size() == limit) {
                        break;
                    }
                }
            }
        }
        return out;
    }

    /** Every event after a sequence, visible to the caller, then live ones as they come, until the subscriber cancels. */
    public Flux<EventEnvelope> stream(long after, List<String> typePrefixes, Caller caller) {
        Predicate<EventEnvelope> filter = filter(typePrefixes, caller);
        return Flux.defer(() -> {
            AtomicLong last = new AtomicLong(after);
            Flux<Long> ticks = Flux.merge(Flux.just(0L), wake.asFlux().onBackpressureDrop(), Flux.interval(POLL));
            return ticks.concatMap(tick -> {
                List<EventEnvelope> batch = ring.after(last.get(), 1000);
                if (!batch.isEmpty()) {
                    last.set(batch.get(batch.size() - 1).seq());
                }
                return Flux.fromIterable(batch).filter(filter);
            }, 1);
        });
    }

    /** What a caller may see: console accounts their account's events only, everyone else all of them. */
    public static boolean visible(EventEnvelope event, Caller caller) {
        if (caller == null || caller.seesEverything() || !caller.isConsoleAccount()) {
            return true;
        }
        return caller.consoleAccount().equals(event.accountId());
    }

    private static Predicate<EventEnvelope> filter(List<String> typePrefixes, Caller caller) {
        List<String> prefixes = typePrefixes == null ? List.of()
                : typePrefixes.stream().map(String::trim).filter(p -> !p.isEmpty()).toList();
        return event -> visible(event, caller) && (prefixes.isEmpty() || prefixes.stream().anyMatch(event::is));
    }
}
