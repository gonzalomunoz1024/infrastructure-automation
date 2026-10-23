package com.iaf.console.bff.notifications.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.iaf.console.bff.live.domain.ConsoleEvents;
import com.iaf.console.bff.notifications.domain.ConsoleDrafts;
import com.iaf.console.bff.notifications.domain.Draft;
import com.iaf.console.bff.notifications.domain.FeedDrafts;
import com.iaf.console.bff.notifications.domain.JobFacts;
import com.iaf.console.kit.events.EventEnvelope;
import com.iaf.console.platform.model.FeedEvent;

/**
 * The events that notify (decision 32.1), as every replica follows them: the platform feed's job
 * events and the console's own events. A job's start is remembered for its later events (its
 * title, and whether it is a row of a batch); an event older than {@code max-event-age} notifies
 * no one, since the feed is replayed from its start when the BFF starts. Work is handed off at
 * once, so the relay and the Kafka consumer never wait on it; an identity change in an account
 * drops the people kept for it.
 */
public class EventNotifications {

    private static final Logger log = LoggerFactory.getLogger(EventNotifications.class);

    private final Notifications notifications;
    private final People people;
    private final Duration maxAge;
    private final Clock clock;
    private final Cache<String, JobFacts> jobs = Caffeine.newBuilder().maximumSize(200_000).expireAfterWrite(Duration.ofDays(3)).build();

    public EventNotifications(Notifications notifications, People people, Duration maxAge, Clock clock) {
        this.notifications = notifications;
        this.people = people;
        this.maxAge = maxAge;
        this.clock = clock;
    }

    public void onFeedEvent(FeedEvent event) {
        if (event == null) {
            return;
        }
        FeedDrafts.started(event).ifPresent(facts -> jobs.put(FeedDrafts.jobIdOf(event), facts));
        if (tooOld(event.at())) {
            return;
        }
        String jobId = FeedDrafts.jobIdOf(event);
        FeedDrafts.of(event, jobId == null ? null : jobs.getIfPresent(jobId)).ifPresent(this::submit);
    }

    public void onConsoleEvent(EventEnvelope event) {
        if (event == null) {
            return;
        }
        if (ConsoleEvents.changesIdentity(event) && event.accountId() != null) {
            people.forget(event.accountId());
        }
        if (tooOld(event.at())) {
            return;
        }
        ConsoleDrafts.of(event).ifPresent(this::submit);
    }

    /** Hands a draft off: addressed and delivered without the caller waiting. */
    public void submit(Draft draft) {
        notifications.accept(draft).subscribe(done -> { }, error -> log.warn("notifying {} ({}) failed: {}", draft.eventKey(), draft.kind(), error.toString()));
    }

    private boolean tooOld(Instant at) {
        return at != null && at.isBefore(clock.instant().minus(maxAge));
    }
}
