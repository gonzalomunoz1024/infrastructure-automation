package com.iaf.console.bff.notifications.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The notifications (decision 32): how long the feed keeps them, how old an event may be and still
 * notify (the platform feed is replayed from its start when the BFF starts), how long an account's
 * people are kept, how the outbox is worked, and the SMTP server notification emails go through
 * (none while {@code email.host} is empty; the demo profile collects them in memory instead).
 */
@ConfigurationProperties(prefix = "console.notifications")
public record NotificationsProperties(
        @DefaultValue("90d") Duration retention,
        @DefaultValue("24h") Duration maxEventAge,
        @DefaultValue("1m") Duration peopleTtl,
        @DefaultValue Outbox outbox,
        @DefaultValue Email email) {

    /** How often the outbox is looked at for what is due, how much one look takes, how long a worker holds it, and how long one try may take. */
    public record Outbox(
            @DefaultValue("5s") Duration poll,
            @DefaultValue("50") int batch,
            @DefaultValue("2m") Duration lease,
            @DefaultValue("30s") Duration timeout) {
    }

    /** The SMTP server; the password comes from the environment and is never logged. */
    public record Email(
            @DefaultValue("") String host,
            @DefaultValue("587") int port,
            @DefaultValue("console@localhost") String from,
            @DefaultValue("") String username,
            @DefaultValue("") String password,
            @DefaultValue("true") boolean starttls) {

        public boolean configured() {
            return host != null && !host.isBlank();
        }

        @Override
        public String toString() {
            return "Email[host=" + host + ", port=" + port + ", from=" + from + ", username=" + username + ", password="
                    + (password == null || password.isEmpty() ? "" : "***") + ", starttls=" + starttls + "]";
        }
    }
}
