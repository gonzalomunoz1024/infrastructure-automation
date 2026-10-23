package com.iaf.console.bff.notifications.adapters.inbound;

import org.springframework.stereotype.Component;

import com.iaf.console.bff.live.application.FeedListener;
import com.iaf.console.bff.notifications.application.EventNotifications;
import com.iaf.console.platform.model.FeedEvent;

/** The platform feed's events, as the live relay follows them, into the notifications. */
@Component
public class FeedNotifications implements FeedListener {

    private final EventNotifications events;

    public FeedNotifications(EventNotifications events) {
        this.events = events;
    }

    @Override
    public void onFeedEvent(FeedEvent event) {
        events.onFeedEvent(event);
    }
}
