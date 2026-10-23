package com.iaf.console.bff.notifications.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

import com.iaf.console.kit.events.EventEnvelope;

/**
 * The console's own events as notifications (decision 32.1):
 * <ul>
 * <li>{@code AccessRequestChanged}, created: the people the identity service lists as its
 * approvers (the admins and the approver groups that may decide it) and the account's admins,
 * not the person who asked; decided (approved, rejected, expired): the person who asked;</li>
 * <li>{@code FindingOpened}: the account's admins (the resource's {@code requestedBy} would need
 * the state service read as the console itself, which the BFF's state client does not do);</li>
 * <li>{@code QuotaChanged} whose use is at 80 % of the limit or more: the account's admins, once
 * per quota, limit and threshold (80, then 100);</li>
 * <li>{@code BudgetThresholdCrossed}: the account's admins.</li>
 * </ul>
 */
public final class ConsoleDrafts {

    static final String ACCESS_REQUEST_CHANGED = "AccessRequestChanged";
    static final String FINDING_OPENED = "FindingOpened";
    static final String QUOTA_CHANGED = "QuotaChanged";
    static final String BUDGET_THRESHOLD_CROSSED = "BudgetThresholdCrossed";
    /** The share of a quota's limit whose use is notified. */
    public static final double QUOTA_SHARE = 0.8;

    private ConsoleDrafts() {
    }

    public static Optional<Draft> of(EventEnvelope event) {
        if (event == null || event.accountId() == null || event.type() == null) {
            return Optional.empty();
        }
        JsonNode data = event.data() == null ? MissingNode.getInstance() : event.data();
        return switch (event.type()) {
            case ACCESS_REQUEST_CHANGED -> accessRequest(event, data);
            case FINDING_OPENED -> finding(event, data);
            case QUOTA_CHANGED -> quota(event, data);
            case BUDGET_THRESHOLD_CROSSED -> budget(event, data);
            default -> Optional.empty();
        };
    }

    private static Instant at(EventEnvelope event) {
        return event.at() == null ? Instant.now() : event.at();
    }

    private static String region(EventEnvelope event) {
        return event.context() == null ? null : event.context().region();
    }

    // ---- access requests

    private static Optional<Draft> accessRequest(EventEnvelope event, JsonNode data) {
        String action = Texts.text(data, "action");
        JsonNode request = data.path("snapshot");
        String requestId = Texts.text(request, "requestId") != null ? Texts.text(request, "requestId") : event.aggregateId();
        if (action == null || requestId == null) {
            return Optional.empty();
        }
        JsonNode requester = request.path("requester");
        String requesterId = Texts.text(requester, "userId");
        String requesterName = Texts.text(requester, "name") != null ? Texts.text(requester, "name")
                : Texts.text(requester, "email") != null ? Texts.text(requester, "email") : requesterId;
        String asked = askedFor(request);
        String link = Links.accessRequest(requestId);
        if ("created".equals(action)) {
            List<String> approvers = new ArrayList<>();
            request.path("approvers").forEach(a -> approvers.add(a.asText()));
            String reason = Texts.text(request, "reason");
            String body = Texts.sentence(Texts.capitalizedFirst(asked)) + (reason == null ? "" : " " + Texts.sentence("“" + Texts.shortened(reason, 300) + "”"));
            return Optional.of(new Draft(event.key(), NotificationKinds.ACCESS_REQUEST_CREATED, Notification.INFO,
                    (requesterName == null ? "Someone" : requesterName) + " asks for access", body, event.accountId(), region(event), null, null, link,
                    at(event), List.of(Audience.people(approvers), Audience.admins()), requesterId == null ? List.of() : List.of(requesterId)));
        }
        if (!List.of("approved", "rejected", "expired").contains(action) || requesterId == null) {
            return Optional.empty();
        }
        String decidedBy = Texts.text(request, "decidedBy");
        String comment = Texts.text(request, "comment");
        String body = Texts.capitalizedFirst(asked) + " in account " + event.accountId()
                + ("expired".equals(action) ? ": no one decided within 30 days." : decidedBy == null ? "." : ", decided by " + decidedBy + ".")
                + (comment == null ? "" : " " + Texts.sentence(comment));
        String severity = "approved".equals(action) ? Notification.INFO : Notification.WARNING;
        return Optional.of(new Draft(event.key(), NotificationKinds.ACCESS_REQUEST_DECIDED, severity, "Your access request was " + action, body,
                event.accountId(), region(event), null, null, link, at(event),
                List.of(Audience.person(requesterId, Texts.text(requester, "email"), Texts.text(requester, "name")))));
    }

    /** What an access request asks for: the role, the action and the resource it names. */
    private static String askedFor(JsonNode request) {
        String role = Texts.text(request, "role");
        String action = Texts.text(request, "action");
        String resource = Texts.text(request, "resource");
        StringBuilder out = new StringBuilder();
        out.append(role != null ? "the " + role + " role" : "access");
        if (action != null) {
            out.append(" for ").append(action);
        }
        if (resource != null) {
            out.append(" on ").append(resource);
        }
        return out.toString();
    }

    // ---- drift

    private static Optional<Draft> finding(EventEnvelope event, JsonNode data) {
        String wfrn = event.context() == null ? null : event.context().wfrn();
        String type = Texts.typeOf(wfrn);
        if (wfrn == null || type == null) {
            return Optional.empty();
        }
        List<String> paths = new ArrayList<>();
        data.path("fields").forEach(f -> {
            if (f.hasNonNull("path")) {
                paths.add(f.get("path").asText());
            }
        });
        String severity = switch (data.path("severity").asText("")) {
            case "critical" -> Notification.CRITICAL;
            case "info" -> Notification.INFO;
            default -> Notification.WARNING;
        };
        String body = paths.isEmpty() ? "What the provider reports differs from what the console expects."
                : (paths.size() == 1 ? "1 field differs" : paths.size() + " fields differ") + " from what the console expects: "
                        + String.join(", ", paths.subList(0, Math.min(5, paths.size()))) + (paths.size() > 5 ? " and more." : ".");
        return Optional.of(new Draft(event.key(), NotificationKinds.DRIFT_FOUND, severity, "Drift found on " + Texts.resourceName(wfrn), body,
                event.accountId(), region(event), wfrn, null, Links.resource(type, wfrn), at(event), List.of(Audience.admins())));
    }

    // ---- quotas

    private static Optional<Draft> quota(EventEnvelope event, JsonNode data) {
        JsonNode quota = data.path("snapshot");
        String key = Texts.text(quota, "key");
        long limit = quota.path("limit").asLong(0);
        long used = quota.path("used").asLong(0);
        if (key == null || limit <= 0 || used < Math.ceil(limit * QUOTA_SHARE)) {
            return Optional.empty();
        }
        int threshold = used >= limit ? 100 : 80;
        String eventKey = NotificationKinds.QUOTA_NEAR_LIMIT + ":" + event.accountId() + ":" + key + ":" + limit + ":" + threshold;
        String body = used + " of " + limit + " used." + (threshold == 100 ? " New requests that need it are refused until the limit is raised." : "");
        return Optional.of(new Draft(eventKey, NotificationKinds.QUOTA_NEAR_LIMIT, threshold == 100 ? Notification.CRITICAL : Notification.WARNING,
                "Quota " + key + " is at " + Texts.percent((double) used / limit) + "%", body, event.accountId(), region(event), null, null,
                Links.quota(key), at(event), List.of(Audience.admins())));
    }

    // ---- budgets

    private static Optional<Draft> budget(EventEnvelope event, JsonNode data) {
        String budgetId = Texts.text(data, "budgetId");
        if (budgetId == null) {
            return Optional.empty();
        }
        String name = Texts.text(data, "name") == null ? budgetId : Texts.text(data, "name");
        int threshold = data.path("threshold").asInt(0);
        String currency = Texts.text(data, "currency") == null ? "" : " " + Texts.text(data, "currency");
        String period = Texts.text(data, "period");
        String body = Texts.money(data.path("spent").asDouble(0)) + " of " + Texts.money(data.path("amount").asDouble(0)) + currency + " spent"
                + (period == null ? "" : " in " + period) + " (" + Math.round(data.path("percent").asDouble(0)) + "%)."
                + (data.path("blockCreates").asBoolean(false) ? " Creates in its scope are refused until the budget is raised." : "");
        return Optional.of(new Draft(event.key(), NotificationKinds.BUDGET_ALERT, threshold >= 100 ? Notification.CRITICAL : Notification.WARNING,
                "Budget " + name + " passed " + threshold + "%", body, event.accountId(), region(event), null, null, Links.budget(budgetId), at(event),
                List.of(Audience.admins())));
    }
}
