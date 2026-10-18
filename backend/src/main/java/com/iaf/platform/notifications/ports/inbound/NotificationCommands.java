package com.iaf.platform.notifications.ports.inbound;

import java.util.List;

import com.iaf.platform.notifications.domain.Notification;
import com.iaf.platform.notifications.ports.inbound.NotificationQueries.NotificationView;

import reactor.core.publisher.Mono;

/** Sending notifications (the notify step and the platform's own listeners) and reading them. */
public interface NotificationCommands {

    /** Where one notification went: the adapters that delivered it ({@code inbox}, {@code slack}, {@code email}). */
    record Delivery(Notification notification, List<String> via, List<String> failed) {
    }

    /** Sends to every delivery adapter; a failing adapter is reported in {@code failed}, never fails the others. */
    Mono<Delivery> send(Notification notification);

    /** Marks one notification read by the caller; 404 when the caller cannot see it. */
    Mono<NotificationView> markRead(String id);
}
