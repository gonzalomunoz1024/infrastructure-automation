package com.iaf.console.bff.live.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Where live events meet open pages: one order of events across accounts, a ring per account
 * (and one for events every account gets, and one per person for their notifications) so a
 * browser that reconnects with {@code Last-Event-ID} replays what it missed, and a multicast sink
 * for what happens next. A cursor older than the ring answers a {@code resync} event first, which
 * tells the page to reload what it shows.
 */
public final class LiveHub {

    private final int capacity;
    private final Supplier<Instant> clock;
    private final Map<String, Deque<Sequenced>> rings = new HashMap<>();
    private final Map<String, Long> oldestDropped = new HashMap<>();
    private final Sinks.Many<Sequenced> sink;
    private long next = 1;

    public LiveHub(int capacity, Supplier<Instant> clock) {
        if (capacity < 1) {
            throw new IllegalArgumentException("the ring needs a capacity");
        }
        this.capacity = capacity;
        this.clock = clock;
        // the sink keeps a short history so an event published between a subscriber's replay
        // snapshot and its subscription is still delivered; the seq filter drops what was replayed
        this.sink = Sinks.many().replay().limit(Math.min(capacity, 1024));
    }

    /** Publishes to one account; answers the event's sequence. */
    public long publish(String accountId, LiveEvent event) {
        Sequenced sequenced;
        synchronized (this) {
            sequenced = new Sequenced(next++, accountId, event);
            Deque<Sequenced> ring = rings.computeIfAbsent(accountId, a -> new ArrayDeque<>());
            if (ring.size() >= capacity) {
                Sequenced dropped = ring.pollFirst();
                oldestDropped.put(accountId, dropped.seq());
            }
            ring.addLast(sequenced);
        }
        // publishers run on many threads (the feed relay, Kafka, requests): a concurrent emit is retried rather than dropped
        try {
            sink.emitNext(sequenced, Sinks.EmitFailureHandler.busyLooping(Duration.ofSeconds(1)));
        } catch (Sinks.EmissionException e) {
            // the ring still holds it, so a stream that reconnects with its cursor replays it
        }
        return sequenced.seq();
    }

    /** Publishes to every account (content and region changes). */
    public long broadcast(LiveEvent event) {
        return publish(Sequenced.EVERY_ACCOUNT, event);
    }

    /** Publishes to one person's streams, whatever account each is in (their notifications). */
    public long publishToPerson(String userId, LiveEvent event) {
        return publish(Sequenced.personKey(userId), event);
    }

    /** The current sequence, which a fresh subscriber may send back later as its cursor. */
    public synchronized long current() {
        return next - 1;
    }

    /**
     * The events of an account after a cursor (null for live only), then everything that follows.
     * When the cursor is older than what the rings keep, a {@code resync} event comes first.
     */
    public Flux<Sequenced> subscribe(String accountId, Long afterSeq) {
        return subscribe(accountId, null, afterSeq);
    }

    /** The same, with the events published to one person ({@link #publishToPerson}) as well; a null user adds none. */
    public Flux<Sequenced> subscribe(String accountId, String userId, Long afterSeq) {
        String person = userId == null ? null : Sequenced.personKey(userId);
        List<Sequenced> replay = new ArrayList<>();
        boolean resync = false;
        long from;
        synchronized (this) {
            from = afterSeq == null ? next - 1 : afterSeq;
            if (afterSeq != null) {
                resync = isTooOld(accountId, afterSeq) || isTooOld(Sequenced.EVERY_ACCOUNT, afterSeq) || person != null && isTooOld(person, afterSeq);
                collect(rings.get(accountId), afterSeq, replay);
                collect(rings.get(Sequenced.EVERY_ACCOUNT), afterSeq, replay);
                if (person != null) {
                    collect(rings.get(person), afterSeq, replay);
                }
                replay.sort((a, b) -> Long.compare(a.seq(), b.seq()));
            }
        }
        final long lastReplayed = replay.isEmpty() ? from : replay.get(replay.size() - 1).seq();
        Flux<Sequenced> live = sink.asFlux()
                .filter(s -> s.accountId().equals(accountId) || s.global() || s.accountId().equals(person))
                .filter(s -> s.seq() > lastReplayed);
        Flux<Sequenced> head = Flux.fromIterable(replay);
        if (resync) {
            head = Flux.concat(Flux.just(new Sequenced(from, accountId, new LiveEvent(LiveEvent.RESYNC, null, clock.get(), null, null, null))), head);
        }
        return Flux.concat(head, live);
    }

    private boolean isTooOld(String accountId, long afterSeq) {
        Long dropped = oldestDropped.get(accountId);
        return dropped != null && afterSeq < dropped;
    }

    private static void collect(Deque<Sequenced> ring, long afterSeq, List<Sequenced> into) {
        if (ring == null) {
            return;
        }
        for (Sequenced s : ring) {
            if (s.seq() > afterSeq) {
                into.add(s);
            }
        }
    }

    public synchronized int size(String accountId) {
        Deque<Sequenced> ring = rings.get(accountId);
        return ring == null ? 0 : ring.size();
    }
}
