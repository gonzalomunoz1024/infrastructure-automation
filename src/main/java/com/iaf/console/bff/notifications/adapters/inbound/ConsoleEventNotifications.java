package com.iaf.console.bff.notifications.adapters.inbound;

import org.springframework.stereotype.Component;

import com.iaf.console.bff.live.application.ConsoleEventListener;
import com.iaf.console.bff.notifications.application.EventNotifications;
import com.iaf.console.kit.events.EventEnvelope;

/** The console's own events (Kafka under the {@code kafka} profile, the demo's stand-ins otherwise) into the notifications. */
@Component
public class ConsoleEventNotifications implements ConsoleEventListener {

    private final EventNotifications events;

    public ConsoleEventNotifications(EventNotifications events) {
        this.events = events;
    }

    @Override
    public void onConsoleEvent(EventEnvelope event) {
        events.onConsoleEvent(event);
    }
}
