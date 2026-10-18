package com.iaf.platform.notifications.application;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Data;

/**
 * {@code platform.notifications.*}: who sees what in the inbox and where else a message goes.
 * {@code channels} are keyed by name without the {@code #} ({@code vm-ops} for {@code #vm-ops});
 * {@code teams} by team (approver groups are teams). Webhook URLs and the mail password are
 * {@code vault:} references, never values.
 */
@Data
@Configuration
@ConfigurationProperties("platform.notifications")
public class NotificationProperties {

    /** In-app notifications kept, newest first. */
    private int keep = 5000;
    /** Events older than this (history being replayed or seeded) notify nobody. */
    private Duration freshFor = Duration.ofMinutes(15);
    /** Where the UI's routes start, for links in chat and email ({@code /jobs/job_1} becomes {@code <link-base>/jobs/job_1}). */
    private String linkBase = "http://localhost:3000/#";
    private Map<String, Channel> channels = new LinkedHashMap<>();
    private Map<String, Team> teams = new LinkedHashMap<>();
    private Slack slack = new Slack();
    private Mail mail = new Mail();

    @Data
    public static class Channel {
        /** Teams whose members see the channel's messages in their inbox. */
        private List<String> teams = new ArrayList<>();
        /** Profile slack: a vault: reference to the channel's incoming-webhook URL. */
        private String webhook = "";
        /** Profile mail: addresses that receive the channel's messages. */
        private List<String> email = new ArrayList<>();
    }

    @Data
    public static class Team {
        /** The team's channel: approval requests and escalations for the team are posted there. */
        private String channel = "";
        /** Profile mail: the team's mailbox. */
        private String email = "";
    }

    @Data
    public static class Slack {
        /** A vault: reference to the webhook for channels without their own; blank = only configured channels. */
        private String defaultWebhook = "";
        private Duration timeout = Duration.ofSeconds(10);
    }

    @Data
    public static class Mail {
        private String from = "iaf@localhost";
        /** A vault: reference to the SMTP password (spring.mail.username names the user); blank = none. */
        private String password = "";
        private String subjectPrefix = "[IAF]";
    }

    /** {@code #VM-Ops} and {@code vm-ops} are the same channel. */
    public static String channelKey(String channel) {
        if (channel == null) {
            return "";
        }
        String c = channel.trim();
        while (c.startsWith("#")) {
            c = c.substring(1);
        }
        return c.toLowerCase(Locale.ROOT);
    }

    public Optional<Channel> channel(String name) {
        String key = channelKey(name);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        return channels.entrySet().stream().filter(e -> channelKey(e.getKey()).equals(key)).map(Map.Entry::getValue).findFirst();
    }

    public Optional<Team> team(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(teams.get(name));
    }

    /** The UI link made absolute. */
    public String absolute(String link) {
        if (link == null || link.isBlank() || link.startsWith("http://") || link.startsWith("https://")) {
            return link;
        }
        String base = linkBase == null ? "" : linkBase.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + (link.startsWith("/") ? link : "/" + link);
    }
}
