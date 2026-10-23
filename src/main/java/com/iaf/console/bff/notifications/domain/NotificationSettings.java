package com.iaf.console.bff.notifications.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.Problem;

/**
 * A person's notification settings (decision 32.2): whether email, Slack and Teams are on as a
 * whole, and per kind the channels it goes to. A kind with no rule of its own goes where
 * {@link NotificationKinds#defaultChannels} says; version 0 is the defaults, never saved. Every
 * replace names the version it was made against and makes the next one.
 */
public record NotificationSettings(long version, Map<String, Boolean> enabled, Map<String, List<String>> rules) {

    public NotificationSettings {
        enabled = enabled == null ? Map.of() : Map.copyOf(enabled);
        rules = rules == null ? Map.of() : Map.copyOf(rules);
    }

    public static NotificationSettings defaults() {
        return new NotificationSettings(0, Map.of(), Map.of());
    }

    /** Whether a channel is on as a whole; the console always is, the others are until switched off. */
    public boolean isOn(String channel) {
        return Channels.CONSOLE.equals(channel) || enabled.getOrDefault(channel, true);
    }

    /** The channels a kind goes to by the person's rule, else by default. */
    public List<String> channelsFor(String kind) {
        List<String> chosen = rules.get(kind);
        return chosen != null ? chosen : NotificationKinds.defaultChannels(kind);
    }

    /** Every kind's channels, in the kinds' order. */
    public Map<String, List<String>> allRules() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String kind : NotificationKinds.ALL) {
            out.put(kind, channelsFor(kind));
        }
        return out;
    }

    /**
     * The channels a notification of a kind reaches the person on: those its rule names that are
     * on as a whole and that can reach them (an address for email, a link for Slack or Teams).
     */
    public List<String> deliveries(String kind, boolean hasAddress, Set<String> linked) {
        List<String> out = new ArrayList<>();
        for (String channel : channelsFor(kind)) {
            if (!isOn(channel)) {
                continue;
            }
            boolean reachable = switch (channel) {
                case Channels.CONSOLE -> true;
                case Channels.EMAIL -> hasAddress;
                default -> linked.contains(channel);
            };
            if (reachable) {
                out.add(channel);
            }
        }
        return out;
    }

    /**
     * The settings a {@code PUT} body asks for, as version {@code nextVersion}: {@code channels.<email|slack|teams>.enabled}
     * (true when left out) and {@code rules.<kind>} (the default when left out). The address and
     * the links are the identity provider's and the chat links', so whatever the body says of them
     * is not read. An unknown kind or channel, or a value of the wrong kind, is a 400 naming each.
     */
    public static NotificationSettings from(JsonNode body, long nextVersion) {
        List<Problem> problems = new ArrayList<>();
        if (body == null || !body.isObject()) {
            throw new InvalidInputException("the settings are an object", List.of(Problem.error("type", "", "the settings are a JSON object")));
        }
        Map<String, Boolean> enabled = new LinkedHashMap<>();
        JsonNode channels = body.path("channels");
        if (!channels.isMissingNode() && !channels.isNull() && !channels.isObject()) {
            problems.add(Problem.error("type", "channels", "channels is an object"));
        }
        for (String channel : Channels.SWITCHABLE) {
            JsonNode flag = channels.path(channel).path("enabled");
            if (flag.isMissingNode() || flag.isNull()) {
                continue;
            }
            if (!flag.isBoolean()) {
                problems.add(Problem.error("type", "channels." + channel + ".enabled", "enabled is true or false"));
            } else {
                enabled.put(channel, flag.booleanValue());
            }
        }
        channels.properties().forEach(e -> {
            if (!Channels.SWITCHABLE.contains(e.getKey())) {
                problems.add(Problem.error("unknown-channel", "channels." + e.getKey(), "the channels are " + Channels.SWITCHABLE + " (the console is always on)"));
            }
        });
        Map<String, List<String>> rules = new LinkedHashMap<>();
        JsonNode given = body.path("rules");
        if (!given.isMissingNode() && !given.isNull() && !given.isObject()) {
            problems.add(Problem.error("type", "rules", "rules is an object of kinds"));
        }
        given.properties().forEach(e -> {
            String kind = e.getKey();
            String field = "rules." + kind;
            if (!NotificationKinds.isKnown(kind)) {
                problems.add(Problem.error("unknown-kind", field, "the kinds are " + NotificationKinds.ALL));
                return;
            }
            if (!e.getValue().isArray()) {
                problems.add(Problem.error("type", field, "a rule is a list of channels"));
                return;
            }
            Set<String> chosen = new LinkedHashSet<>();
            for (JsonNode c : e.getValue()) {
                if (!c.isTextual() || !Channels.isKnown(c.asText())) {
                    problems.add(Problem.error("unknown-channel", field, "the channels are " + Channels.ALL));
                    return;
                }
                chosen.add(c.asText());
            }
            rules.put(kind, List.copyOf(chosen));
        });
        if (!problems.isEmpty()) {
            throw new InvalidInputException("the notification settings are not valid", problems);
        }
        return new NotificationSettings(nextVersion, enabled, rules);
    }
}
