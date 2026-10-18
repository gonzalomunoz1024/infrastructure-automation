package com.iaf.platform.notifications.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import com.iaf.platform.common.domain.Identity;

import lombok.Builder;
import lombok.With;

/**
 * One message for people: a {@code notify} step, a provider alert, an approval waiting for a group,
 * an escalated approval. It is addressed to people ({@code users}), teams ({@code teams}) and
 * optionally a {@code channel} (like {@code #vm-ops}); each delivery adapter decides what that
 * means for it (the inbox shows it to the users and the members of the teams, a chat webhook posts
 * it to the channel, email writes to the addresses configured for them).
 *
 * <p>{@code kind} is {@code notify}, {@code provider.alert}, {@code approval.requested} or
 * {@code approval.escalated}; {@code severity} is info, warning or critical; {@code link} is a path
 * in the UI, like {@code /jobs/job_123}.
 */
@Builder(toBuilder = true)
@With
public record Notification(
        String id,
        Instant at,
        String kind,
        String severity,
        String title,
        String message,
        String link,
        String channel,
        List<String> users,
        List<String> teams,
        String jobId,
        String workflow,
        String provider) {

    public static final String NOTIFY = "notify";
    public static final String PROVIDER_ALERT = "provider.alert";
    public static final String APPROVAL_REQUESTED = "approval.requested";
    public static final String APPROVAL_ESCALATED = "approval.escalated";

    public Notification {
        users = distinct(users);
        teams = distinct(teams);
        severity = severity == null || severity.isBlank() ? "info" : severity;
    }

    /** True when {@code who} is one of the users or in one of the teams. */
    public boolean addressedTo(Identity who) {
        if (who == null) {
            return false;
        }
        return users.contains(who.id()) || (who.teams() != null && who.teams().stream().anyMatch(teams::contains));
    }

    /** The same notification with more teams in its audience. */
    public Notification withMoreTeams(List<String> more) {
        if (more == null || more.isEmpty()) {
            return this;
        }
        List<String> all = new ArrayList<>(teams);
        all.addAll(more);
        return withTeams(all);
    }

    private static List<String> distinct(List<String> values) {
        if (values == null) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                out.add(v.trim());
            }
        }
        return List.copyOf(out);
    }
}
