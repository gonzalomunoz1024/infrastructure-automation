package com.iaf.platform.notifications.adapters.outbound;

import org.springframework.stereotype.Component;

import com.iaf.platform.notifications.domain.Notification;
import com.iaf.platform.notifications.ports.outbound.InboxStore;
import com.iaf.platform.notifications.ports.outbound.NotificationPort;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * The default delivery: the in-app inbox ({@code GET /api/v1/notifications}). Always on, whatever
 * else is: the inbox is where every notification can be found again. A notification with nobody
 * to show it to (no user, no team) is not kept.
 */
@Component
public class InAppNotifications implements NotificationPort {

    private final InboxStore inbox;

    public InAppNotifications(InboxStore inbox) {
        this.inbox = inbox;
    }

    @Override
    public String name() {
        return "inbox";
    }

    @Override
    public Mono<Boolean> deliver(Notification n) {
        if (n.users().isEmpty() && n.teams().isEmpty()) {
            return Mono.just(false);
        }
        return Mono.fromCallable(() -> {
            inbox.save(n);
            return true;
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
