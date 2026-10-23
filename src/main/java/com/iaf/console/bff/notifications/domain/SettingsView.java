package com.iaf.console.bff.notifications.domain;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code GET /console/v1/notification-settings} (decision 32.2): the version, each channel with
 * what makes it reach the person (the identity provider's address for email, a chat link for
 * Slack and Teams) and whether it is on, and every kind's channels.
 */
public record SettingsView(long version, ChannelsView channels, Map<String, List<String>> rules) {

    public record ChannelsView(Email email, Chat slack, Chat teams) {
    }

    public record Email(String address, boolean enabled) {
    }

    public record Chat(boolean linked, boolean enabled) {
    }

    public static SettingsView of(NotificationSettings settings, String address, Set<String> linked) {
        return new SettingsView(settings.version(), new ChannelsView(new Email(address == null || address.isBlank() ? null : address,
                settings.isOn(Channels.EMAIL)), new Chat(linked.contains(Channels.SLACK), settings.isOn(Channels.SLACK)),
                new Chat(linked.contains(Channels.TEAMS), settings.isOn(Channels.TEAMS))), settings.allRules());
    }
}
