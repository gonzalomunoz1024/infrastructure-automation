package com.iaf.console.bff.notifications.domain;

import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One notification for one person (decision 32.1): its kind and severity, what to show, the
 * account (and region, resource or job) it is about, and the console path that opens it. The id
 * comes from the event's key and the person, so every replica that sees the event makes the same
 * notification. {@code readAt} is absent while it is unread.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Notification(String id, String kind, String severity, String title, String body, String accountId, String region, String wfrn,
        String jobId, String link, Instant createdAt, Instant readAt) {

    public static final String INFO = "info";
    public static final String WARNING = "warning";
    public static final String CRITICAL = "critical";

    public Notification {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(link, "link");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** The same notification read at a time; one already read keeps its first time. */
    public Notification read(Instant at) {
        return readAt != null ? this : new Notification(id, kind, severity, title, body, accountId, region, wfrn, jobId, link, createdAt, at);
    }
}
