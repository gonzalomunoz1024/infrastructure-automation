package com.iaf.console.fake.events.domain;

import java.util.ArrayList;
import java.util.List;

import com.iaf.console.kit.events.EventEnvelope;

/**
 * The in-memory outbox: a bounded ring of the last events, each with a sequence from one counter,
 * so the feed has one total order and the oldest events fall off once the ring is full.
 */
public final class EventRing {

    /** How many events the platform's in-memory outbox keeps. */
    public static final int DEFAULT_CAPACITY = 50_000;

    private final EventEnvelope[] slots;
    private long nextSeq = 1;
    private int size;
    private int head;

    public EventRing(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("the ring needs room for one event");
        }
        this.slots = new EventEnvelope[capacity];
    }

    public int capacity() {
        return slots.length;
    }

    /** Stores an event under the next sequence and answers it with the sequence set. */
    public synchronized EventEnvelope append(EventEnvelope event) {
        EventEnvelope stored = event.withSeq(nextSeq++);
        int index = (head + size) % slots.length;
        if (size == slots.length) {
            slots[head] = stored;
            head = (head + 1) % slots.length;
        } else {
            slots[index] = stored;
            size++;
        }
        return stored;
    }

    /** The last sequence handed out, 0 before the first event. */
    public synchronized long lastSeq() {
        return nextSeq - 1;
    }

    /** The oldest sequence still in the ring, 0 when the ring is empty. */
    public synchronized long firstSeq() {
        return size == 0 ? 0 : slots[head].seq();
    }

    public synchronized int size() {
        return size;
    }

    /** At most {@code limit} events with a sequence above {@code after}, oldest first. */
    public synchronized List<EventEnvelope> after(long after, int limit) {
        List<EventEnvelope> out = new ArrayList<>();
        if (size == 0 || limit < 1) {
            return out;
        }
        long first = slots[head].seq();
        long start = Math.max(after + 1, first);
        long last = nextSeq - 1;
        for (long seq = start; seq <= last && out.size() < limit; seq++) {
            out.add(slots[(int) ((head + (seq - first)) % slots.length)]);
        }
        return out;
    }

    /** Every event in the ring, oldest first. */
    public synchronized List<EventEnvelope> all() {
        return after(0, size);
    }
}
