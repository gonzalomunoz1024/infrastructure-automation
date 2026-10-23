package com.iaf.console.bff.notifications.domain;

import java.util.List;
import java.util.Set;

/**
 * What the console notifies (decision 32.1), and the channels each kind goes to until the person
 * chooses otherwise: the console for every kind; email, and Slack and Teams once linked, for the
 * kinds that ask someone to act or that end badly, and for the weekly digest.
 */
public final class NotificationKinds {

    public static final String JOB_SUCCEEDED = "job.succeeded";
    public static final String JOB_FAILED = "job.failed";
    public static final String JOB_NEEDS_ATTENTION = "job.needs-attention";
    public static final String APPROVAL_REQUESTED = "approval.requested";
    public static final String APPROVAL_DECIDED = "approval.decided";
    public static final String ACCESS_REQUEST_CREATED = "access-request.created";
    public static final String ACCESS_REQUEST_DECIDED = "access-request.decided";
    public static final String DRIFT_FOUND = "drift.found";
    public static final String QUOTA_NEAR_LIMIT = "quota.near-limit";
    public static final String BUDGET_ALERT = "budget.alert";
    public static final String DIGEST_READY = "digest.ready";
    /** A test the person sent from their settings; it has no rule of its own. */
    public static final String TEST = "test";

    /** Every kind a rule may name, in the order the settings list them. */
    public static final List<String> ALL = List.of(JOB_SUCCEEDED, JOB_FAILED, JOB_NEEDS_ATTENTION, APPROVAL_REQUESTED, APPROVAL_DECIDED,
            ACCESS_REQUEST_CREATED, ACCESS_REQUEST_DECIDED, DRIFT_FOUND, QUOTA_NEAR_LIMIT, BUDGET_ALERT, DIGEST_READY);

    private static final Set<String> BEYOND_THE_CONSOLE = Set.of(JOB_FAILED, JOB_NEEDS_ATTENTION, APPROVAL_REQUESTED, ACCESS_REQUEST_CREATED,
            ACCESS_REQUEST_DECIDED, BUDGET_ALERT, DIGEST_READY);

    private NotificationKinds() {
    }

    public static boolean isKnown(String kind) {
        return kind != null && ALL.contains(kind);
    }

    /** The channels a kind goes to by default; Slack and Teams are listed but reach only a person who linked them. */
    public static List<String> defaultChannels(String kind) {
        return BEYOND_THE_CONSOLE.contains(kind) ? Channels.ALL : List.of(Channels.CONSOLE);
    }
}
