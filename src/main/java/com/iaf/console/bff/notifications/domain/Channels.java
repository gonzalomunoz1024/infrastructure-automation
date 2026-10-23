package com.iaf.console.bff.notifications.domain;

import java.util.List;
import java.util.Set;

/**
 * Where a notification can go (decision 32.2): the console (the feed and the live stream), email
 * (the person's identity-provider address, never one they type), and Slack or Teams once the
 * person linked a chat identity (decision 31.3).
 */
public final class Channels {

    public static final String CONSOLE = "console";
    public static final String EMAIL = "email";
    public static final String SLACK = "slack";
    public static final String TEAMS = "teams";

    public static final List<String> ALL = List.of(CONSOLE, EMAIL, SLACK, TEAMS);
    /** The channels a person can switch off as a whole; the console is always there. */
    public static final List<String> SWITCHABLE = List.of(EMAIL, SLACK, TEAMS);
    /** The chat platforms, which reach a person only once they linked one. */
    public static final Set<String> CHAT = Set.of(SLACK, TEAMS);

    private Channels() {
    }

    public static boolean isKnown(String channel) {
        return channel != null && ALL.contains(channel);
    }
}
