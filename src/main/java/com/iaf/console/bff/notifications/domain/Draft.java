package com.iaf.console.bff.notifications.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A notification before it is addressed: what an event says, about which account (and region,
 * resource or job), the console path that opens it, who it is for and who not (the person whose
 * own act it reports), and the event's deterministic key, which makes it once per person and
 * channel however many replicas see the event.
 */
public record Draft(String eventKey, String kind, String severity, String title, String body, String accountId, String region, String wfrn,
        String jobId, String link, Instant at, List<Audience> audience, List<String> exclude) {

    /** The region a notification names when its event names none (identity and cost events are region-less). */
    public static final String NO_REGION = "global";

    public Draft {
        Objects.requireNonNull(eventKey, "eventKey");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(link, "link");
        Objects.requireNonNull(at, "at");
        region = region == null || region.isBlank() ? NO_REGION : region;
        audience = audience == null ? List.of() : List.copyOf(audience);
        exclude = exclude == null ? List.of() : exclude.stream().filter(Objects::nonNull).toList();
    }

    public Draft(String eventKey, String kind, String severity, String title, String body, String accountId, String region, String wfrn,
            String jobId, String link, Instant at, List<Audience> audience) {
        this(eventKey, kind, severity, title, body, accountId, region, wfrn, jobId, link, at, audience, List.of());
    }

    /** The notification this draft makes for one person, unread. */
    public Notification forPerson(String userId) {
        return new Notification(NotificationIds.of(eventKey, userId), kind, severity, title, body, accountId, region, wfrn, jobId, link, at, null);
    }
}
