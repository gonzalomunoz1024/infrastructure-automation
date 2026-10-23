package com.iaf.console.bff.session.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Which actions need a fresh authentication and how fresh: an action matching one of the
 * patterns ({@code <service>:<verb>} with {@code *}) is allowed only when the identity provider
 * saw the user authenticate within {@code maxAge}; otherwise the console sends them back to the
 * provider with {@code prompt=login} and comes back to the same session.
 */
public record StepUpPolicy(Duration maxAge, List<String> actions) {

    public StepUpPolicy {
        Objects.requireNonNull(maxAge, "maxAge");
        actions = actions == null ? List.of() : List.copyOf(actions);
        if (maxAge.isNegative() || maxAge.isZero()) {
            throw new IllegalArgumentException("the step-up max age must be positive");
        }
    }

    /** Whether an action is one the policy guards. */
    public boolean guards(String action) {
        for (String pattern : actions) {
            if (ActionPatterns.matches(pattern, action)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a session authenticated recently enough for a guarded action. */
    public boolean fresh(Session session, Instant now) {
        return !session.authTime().plus(maxAge).isBefore(now);
    }

    /** True when the action is guarded and the session is not fresh enough. */
    public boolean requiresStepUp(Session session, String action, Instant now) {
        return guards(action) && !fresh(session, now);
    }
}
