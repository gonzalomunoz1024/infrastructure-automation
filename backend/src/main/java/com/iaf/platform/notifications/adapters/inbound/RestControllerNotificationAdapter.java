package com.iaf.platform.notifications.adapters.inbound;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.iaf.platform.notifications.application.NotificationHandler;
import com.iaf.platform.notifications.domain.Notification;
import com.iaf.platform.notifications.ports.inbound.NotificationCommands;
import com.iaf.platform.notifications.ports.inbound.NotificationQueries;
import com.iaf.platform.notifications.ports.inbound.NotificationQueries.NotificationView;

import reactor.core.publisher.Mono;

/**
 * The caller's in-app inbox: {@code GET /api/v1/notifications?unread=} (addressed to them or to one
 * of their teams, newest first, with the unread count) and {@code POST /api/v1/notifications/{id}:read}.
 */
@RestController
@RequestMapping("/api/v1")
public class RestControllerNotificationAdapter implements NotificationQueries, NotificationCommands {

    private final NotificationHandler handler;

    public RestControllerNotificationAdapter(NotificationHandler handler) {
        this.handler = handler;
    }

    @Override
    @GetMapping("/notifications")
    public Mono<Inbox> inbox(@RequestParam(required = false) Boolean unread) {
        return handler.inbox(unread);
    }

    @Override
    @PostMapping("/notifications/{id}:read")
    public Mono<NotificationView> markRead(@PathVariable String id) {
        return handler.markRead(id);
    }

    /** Programmatic (the notify step and the platform's listeners send); not an endpoint. */
    @Override
    public Mono<Delivery> send(Notification notification) {
        return handler.send(notification);
    }
}
