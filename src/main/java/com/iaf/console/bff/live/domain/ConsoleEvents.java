package com.iaf.console.bff.live.domain;

import java.time.Instant;
import java.util.Optional;

import com.iaf.console.kit.events.EventEnvelope;

/**
 * The console's own events (state.resources, drift.findings, identity.changes, cost.events) as
 * live events, scoped on {@code context.accountId}. Of cost.events only a threshold crossed reaches
 * the pages, as {@code budget.alert} with the event's data (budgetId, name, period, threshold,
 * percent, spent, amount, forecast, currency, blockCreates); budget changes and chargeback months
 * do not.
 */
public final class ConsoleEvents {

    /** The cost service's event of a budget's spend crossing a threshold. */
    public static final String BUDGET_THRESHOLD_CROSSED = "BudgetThresholdCrossed";

    private ConsoleEvents() {
    }

    public static Optional<PlatformEvents.Mapped> toLive(EventEnvelope envelope) {
        if (envelope == null || envelope.accountId() == null) {
            return Optional.empty();
        }
        String type = envelope.type();
        if (BUDGET_THRESHOLD_CROSSED.equals(type)) {
            type = LiveEvent.BUDGET_ALERT;
        } else if ("budget".equals(envelope.aggregate()) || "chargeback".equals(envelope.aggregate())) {
            return Optional.empty();
        }
        String wfrn = envelope.context() == null ? null : envelope.context().wfrn();
        Long version = envelope.data() != null && envelope.data().hasNonNull("version") ? envelope.data().get("version").asLong() : null;
        Instant at = envelope.at() == null ? Instant.now() : envelope.at();
        return Optional.of(new PlatformEvents.Mapped(envelope.accountId(), new LiveEvent(type, wfrn, at, version, envelope.key(), envelope.data())));
    }

    /**
     * Whether an event changes who may do what in its account: the identity records, and since
     * decision 34 a guardrail over the account, an access review's revoke and an elevation.
     */
    public static boolean changesIdentity(EventEnvelope envelope) {
        String type = envelope.type() == null ? "" : envelope.type();
        return type.endsWith("Changed") && envelope.aggregate() != null
                && switch (envelope.aggregate()) {
                    case "account", "user", "group", "role", "policy", "accessKey", "quota", "guardrail", "access-review", "elevation" -> true;
                    default -> false;
                };
    }
}
