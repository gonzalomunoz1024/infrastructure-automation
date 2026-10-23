package com.iaf.console.bff.notifications.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One email or chat message waiting in the outbox (decision 32.2), keyed
 * {@code <eventKey>:<userId>:<channel>}: whom it is for and what, how many tries it had, when the
 * next is due, the last error, and until when a worker holds it. A sent or failed entry stays
 * for the record until the outbox lets it go.
 */
public record OutboxEntry(String key, String channel, Recipient recipient, Notification notification, String status, int attempts,
        Instant nextAttemptAt, String lastError, Instant leaseUntil, Instant createdAt, Instant updatedAt) {

    public static final String PENDING = "pending";
    public static final String SENT = "sent";
    public static final String FAILED = "failed";

    public OutboxEntry {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(notification, "notification");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** A new entry, due at once. */
    public static OutboxEntry pending(String key, String channel, Recipient recipient, Notification notification, Instant now) {
        return new OutboxEntry(key, channel, recipient, notification, PENDING, 0, now, null, null, now, now);
    }

    public boolean dueBy(Instant now) {
        return PENDING.equals(status) && !nextAttemptAt.isAfter(now) && (leaseUntil == null || !leaseUntil.isAfter(now));
    }

    /** The same entry held by a worker until {@code now + lease}. */
    public OutboxEntry held(Instant now, Duration lease) {
        return new OutboxEntry(key, channel, recipient, notification, status, attempts, nextAttemptAt, lastError, now.plus(lease), createdAt, now);
    }

    public OutboxEntry sent(Instant now) {
        return new OutboxEntry(key, channel, recipient, notification, SENT, attempts + 1, nextAttemptAt, null, null, createdAt, now);
    }

    /** After a failed try: due again after the schedule's wait, or failed when that was the last try. */
    public OutboxEntry failedTry(String error, Instant now) {
        int tries = attempts + 1;
        Optional<Duration> wait = RetrySchedule.after(tries);
        return wait.map(w -> new OutboxEntry(key, channel, recipient, notification, PENDING, tries, now.plus(w), error, null, createdAt, now))
                .orElseGet(() -> new OutboxEntry(key, channel, recipient, notification, FAILED, tries, nextAttemptAt, error, null, createdAt, now));
    }
}
