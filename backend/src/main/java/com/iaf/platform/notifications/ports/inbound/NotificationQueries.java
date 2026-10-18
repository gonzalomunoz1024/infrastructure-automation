package com.iaf.platform.notifications.ports.inbound;

import java.time.Instant;
import java.util.List;

import reactor.core.publisher.Mono;

/** The caller's in-app inbox: what was addressed to them or to one of their teams. */
public interface NotificationQueries {

    /** One notification as its reader sees it: {@code read} is theirs alone. Shape as in docs/api.md. */
    record NotificationView(String id, Instant at, String kind, String severity, String title, String message, String link, String channel,
            String jobId, String workflow, String provider, List<String> users, List<String> teams, boolean read) {
    }

    record Inbox(List<NotificationView> items, int unread) {
    }

    /** Newest first; {@code unread} true keeps only what the caller has not read, false only what they have. */
    Mono<Inbox> inbox(Boolean unread);
}
