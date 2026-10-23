package com.iaf.console.bff.notifications.ports.outbound;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import com.iaf.console.bff.notifications.domain.FeedQuery;
import com.iaf.console.bff.notifications.domain.Notification;
import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Mono;

/**
 * The people's notification feeds (decision 32.3), kept 90 days. A notification's id is unique,
 * and comes from the event's key and the person, so the first insert of an event for a person
 * wins on every replica: the console channel's once-only key.
 */
@GlobalRead("a person's own notifications, kept by person across their accounts; every read names the accounts the session still holds")
public interface NotificationFeed {

    /** Stores a person's notification unless one of its id is there; true when this call stored it. */
    Mono<Boolean> insert(String userId, Notification notification);

    /** The person's notifications the query covers, newest first, after its cursor: at most {@code limit + 1}. */
    Mono<List<Notification>> list(String userId, FeedQuery query);

    /** How many of the person's notifications the query covers are unread (its cursor and limit aside). */
    Mono<Long> countUnread(String userId, FeedQuery query);

    /** Marks the person's notifications of these ids read; another person's ids change nothing. */
    Mono<Void> markRead(String userId, Collection<String> ids, Instant at);

    /** Marks every notification of the person read. */
    Mono<Void> markAllRead(String userId, Instant at);
}
