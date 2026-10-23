package com.iaf.console.bff.notifications.adapters.outbound;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.console.bff.notifications.application.NotificationsProperties;
import com.iaf.console.bff.notifications.domain.FeedQuery;
import com.iaf.console.bff.notifications.domain.Notification;
import com.iaf.console.bff.notifications.ports.outbound.NotificationFeed;

import reactor.core.publisher.Mono;

/** The feeds of one instance, in memory: a notification older than the retention is dropped as the next one arrives. */
@Component
@Profile("!mongo")
public class InMemoryNotificationFeed implements NotificationFeed {

    private final Map<String, Map<String, Notification>> byUser = new HashMap<>();
    private final NotificationsProperties properties;
    private final Clock clock;

    public InMemoryNotificationFeed(NotificationsProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Mono<Boolean> insert(String userId, Notification notification) {
        return Mono.fromCallable(() -> {
            synchronized (byUser) {
                Map<String, Notification> feed = byUser.computeIfAbsent(userId, u -> new HashMap<>());
                Instant oldest = clock.instant().minus(properties.retention());
                feed.values().removeIf(n -> n.createdAt().isBefore(oldest));
                return feed.putIfAbsent(notification.id(), notification) == null;
            }
        });
    }

    @Override
    public Mono<List<Notification>> list(String userId, FeedQuery query) {
        return Mono.fromCallable(() -> {
            synchronized (byUser) {
                return byUser.getOrDefault(userId, Map.of()).values().stream().filter(query::covers).filter(query::afterCursor)
                        .sorted(FeedQuery.NEWEST_FIRST).limit(query.limit() + 1L).toList();
            }
        });
    }

    @Override
    public Mono<Long> countUnread(String userId, FeedQuery query) {
        FeedQuery unread = query.forCount();
        return Mono.fromCallable(() -> {
            synchronized (byUser) {
                return byUser.getOrDefault(userId, Map.of()).values().stream().filter(unread::covers).count();
            }
        });
    }

    @Override
    public Mono<Void> markRead(String userId, Collection<String> ids, Instant at) {
        return Mono.fromRunnable(() -> {
            synchronized (byUser) {
                Map<String, Notification> feed = byUser.getOrDefault(userId, Map.of());
                for (String id : ids) {
                    feed.computeIfPresent(id, (k, n) -> n.read(at));
                }
            }
        });
    }

    @Override
    public Mono<Void> markAllRead(String userId, Instant at) {
        return Mono.fromRunnable(() -> {
            synchronized (byUser) {
                byUser.getOrDefault(userId, Map.of()).replaceAll((k, n) -> n.read(at));
            }
        });
    }
}
