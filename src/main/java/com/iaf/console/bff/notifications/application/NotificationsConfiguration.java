package com.iaf.console.bff.notifications.application;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.iaf.console.bff.notifications.ports.outbound.ChatLinksLookup;
import com.iaf.console.bff.notifications.ports.outbound.NotificationChannel;
import com.iaf.console.bff.notifications.ports.outbound.NotificationFeed;
import com.iaf.console.bff.notifications.ports.outbound.NotificationOutbox;
import com.iaf.console.bff.notifications.ports.outbound.RecipientDirectory;
import com.iaf.console.bff.notifications.ports.outbound.SettingsStore;

/** Wires the notifications: the people cache, the channels the adapters bring, the outbox worker and the event intake. */
@Configuration(proxyBeanMethods = false)
public class NotificationsConfiguration {

    private static final Logger log = LoggerFactory.getLogger(NotificationsConfiguration.class);

    @Bean
    public People notificationPeople(RecipientDirectory directory, NotificationsProperties properties) {
        return new People(directory, properties.peopleTtl());
    }

    @Bean
    public OutboxWorker notificationOutboxWorker(NotificationOutbox outbox, ObjectProvider<NotificationChannel> channels, NotificationsProperties properties,
            Clock clock) {
        return new OutboxWorker(outbox, byId(channels), properties.outbox(), clock);
    }

    @Bean
    public Notifications notifications(NotificationFeed feed, NotificationOutbox outbox, SettingsStore settings, People people, ChatLinksLookup chatLinks,
            ObjectProvider<NotificationChannel> channels, OutboxWorker worker, Clock clock) {
        Map<String, NotificationChannel> byId = byId(channels);
        log.info("notification channels: {}", byId.keySet());
        return new Notifications(feed, outbox, settings, people, chatLinks, byId, worker, clock);
    }

    @Bean
    public NotificationPreferences notificationPreferences(SettingsStore store, ChatLinksLookup chatLinks) {
        return new NotificationPreferences(store, chatLinks);
    }

    @Bean
    public EventNotifications eventNotifications(Notifications notifications, People people, NotificationsProperties properties, Clock clock) {
        return new EventNotifications(notifications, people, properties.maxEventAge(), clock);
    }

    /** The channels by name; the first of a name wins. */
    private static Map<String, NotificationChannel> byId(ObjectProvider<NotificationChannel> channels) {
        Map<String, NotificationChannel> out = new LinkedHashMap<>();
        channels.orderedStream().forEach(c -> out.putIfAbsent(c.id(), c));
        return out;
    }
}
