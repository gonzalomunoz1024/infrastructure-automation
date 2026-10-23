package com.iaf.console.bff.notifications.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.iaf.console.bff.notifications.adapters.outbound.InMemoryNotificationFeed;
import com.iaf.console.bff.notifications.adapters.outbound.InMemoryNotificationOutbox;
import com.iaf.console.bff.notifications.application.NotificationsProperties;

/** Decision 32.2: exactly once per event, person and channel, and the outbox's schedule of tries. */
class DeliveryTest {

    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");

    @Test
    void theKeysComeFromTheEventThePersonAndTheChannel() {
        String id = NotificationIds.of("job.finished:job_1:3", "user_dana");
        assertEquals(id, NotificationIds.of("job.finished:job_1:3", "user_dana"), "the same on every replica");
        assertTrue(id.matches("ntf_[0-9a-f]{32}"), id);
        assertNotEquals(id, NotificationIds.of("job.finished:job_1:3", "user_mia"));
        assertNotEquals(id, NotificationIds.of("job.finished:job_2:3", "user_dana"));
        assertEquals("job.finished:job_1:3:user_dana:email", NotificationIds.deliveryKey("job.finished:job_1:3", "user_dana", "email"));
    }

    @Test
    void theFirstInsertOfAKeyWinsInTheFeedAndTheOutbox() {
        NotificationsProperties properties = new NotificationsProperties(Duration.ofDays(90), Duration.ofHours(24), Duration.ofMinutes(1),
                new NotificationsProperties.Outbox(Duration.ofSeconds(5), 50, Duration.ofMinutes(2), Duration.ofSeconds(30)),
                new NotificationsProperties.Email("", 587, "console@localhost", "", "", true));
        InMemoryNotificationFeed feed = new InMemoryNotificationFeed(properties, Clock.fixed(NOW, ZoneOffset.UTC));
        Notification n = draft().forPerson("user_dana");
        assertTrue(feed.insert("user_dana", n).block());
        assertFalse(feed.insert("user_dana", draft().forPerson("user_dana")).block(), "a second replica's insert of the same event");
        InMemoryNotificationOutbox outbox = new InMemoryNotificationOutbox();
        Recipient dana = new Recipient("user_dana", "dana@example.com", "Dana");
        String key = NotificationIds.deliveryKey("job.finished:job_1:3", "user_dana", "email");
        assertTrue(outbox.enqueue(OutboxEntry.pending(key, "email", dana, n, NOW)).block());
        assertFalse(outbox.enqueue(OutboxEntry.pending(key, "email", dana, n, NOW)).block());
        assertEquals(1, outbox.entries().size());
    }

    @Test
    void aFailedSendIsTriedAgainAfter1And5And30MinutesAnd2HoursThenFailed() {
        assertEquals(Optional.of(Duration.ofMinutes(1)), RetrySchedule.after(1));
        assertEquals(Optional.of(Duration.ofMinutes(5)), RetrySchedule.after(2));
        assertEquals(Optional.of(Duration.ofMinutes(30)), RetrySchedule.after(3));
        assertEquals(Optional.of(Duration.ofHours(2)), RetrySchedule.after(4));
        assertEquals(Optional.empty(), RetrySchedule.after(5));
        OutboxEntry entry = OutboxEntry.pending("k", "email", new Recipient("user_dana", "dana@example.com", "Dana"), draft().forPerson("user_dana"), NOW);
        assertTrue(entry.dueBy(NOW));
        Instant at = NOW;
        List<Duration> waited = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            entry = entry.failedTry("smtp down", at);
            assertEquals(OutboxEntry.PENDING, entry.status());
            waited.add(Duration.between(at, entry.nextAttemptAt()));
            assertFalse(entry.dueBy(at));
            at = entry.nextAttemptAt();
            assertTrue(entry.dueBy(at));
        }
        assertEquals(RetrySchedule.DELAYS, waited);
        entry = entry.failedTry("smtp down", at);
        assertEquals(OutboxEntry.FAILED, entry.status());
        assertEquals(5, entry.attempts());
        assertFalse(entry.dueBy(at.plus(Duration.ofDays(1))), "a failed delivery is not tried again");
        assertEquals("smtp down", entry.lastError());
        OutboxEntry sent = OutboxEntry.pending("k", "email", new Recipient("user_dana", "dana@example.com", "Dana"), draft().forPerson("user_dana"), NOW)
                .sent(NOW);
        assertEquals(OutboxEntry.SENT, sent.status());
        assertFalse(sent.dueBy(NOW.plus(Duration.ofDays(1))), "a sent delivery is not sent again");
    }

    private static Draft draft() {
        return new Draft("job.finished:job_1:3", NotificationKinds.JOB_FAILED, Notification.CRITICAL, "Restart vm failed", "It failed.",
                "123456789012", "global", null, "job_1", Links.job("job_1"), NOW, List.of(Audience.person("user_dana", null, null)));
    }
}
