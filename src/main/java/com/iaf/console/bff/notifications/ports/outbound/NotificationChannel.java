package com.iaf.console.bff.notifications.ports.outbound;

import com.iaf.console.bff.notifications.domain.Notification;
import com.iaf.console.bff.notifications.domain.Recipient;

import reactor.core.publisher.Mono;

/**
 * One way a notification reaches a person (decision 32.2): {@code console} (the live stream; the
 * feed holds it already), {@code email}, {@code slack} or {@code teams}. Email and chat messages
 * come here from the outbox, which tries a failed delivery again on the schedule; an error is a
 * failed try.
 */
public interface NotificationChannel {

    /** The channel's name, as the settings' rules name it. */
    String id();

    Mono<Void> deliver(Recipient recipient, Notification notification);
}
