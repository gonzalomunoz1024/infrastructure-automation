package com.iaf.console.bff.notifications.adapters.outbound;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.bff.live.domain.LiveEvent;
import com.iaf.console.bff.live.domain.LiveHub;
import com.iaf.console.bff.notifications.domain.Channels;
import com.iaf.console.bff.notifications.domain.Notification;
import com.iaf.console.bff.notifications.domain.Recipient;
import com.iaf.console.bff.notifications.ports.outbound.NotificationChannel;

import reactor.core.publisher.Mono;

/**
 * The console channel's live half: a {@code notification} event, the Notification in its data and
 * its id as the key, on every stream the person has open on this replica, whatever its account.
 * The feed's insert, the channel's once-only half, comes first.
 */
@Component
public class ConsoleChannel implements NotificationChannel {

    private final LiveHub hub;
    private final ObjectMapper json;

    public ConsoleChannel(LiveHub hub, ObjectMapper json) {
        this.hub = hub;
        this.json = json;
    }

    @Override
    public String id() {
        return Channels.CONSOLE;
    }

    @Override
    public Mono<Void> deliver(Recipient recipient, Notification notification) {
        return Mono.fromRunnable(() -> hub.publishToPerson(recipient.userId(), new LiveEvent(LiveEvent.NOTIFICATION, notification.wfrn(),
                notification.createdAt(), null, notification.id(), json.valueToTree(notification))));
    }
}
