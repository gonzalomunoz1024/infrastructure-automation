package com.iaf.console.bff.session.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.iaf.console.bff.access.domain.Decision;
import com.iaf.console.bff.access.domain.Denial;

/**
 * The {@code Session} the web app reads: who, where, the CSRF token, the step-up state, and the
 * decisions on the account's own console actions ({@code decisions}, keyed by
 * {@code AccountAction}'s {@code <kind>:<verb>}; absent when the identity service could not
 * answer) and whether the person is an organization admin (decided at sign-in from the identity
 * provider's groups).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionView(User user, Account account, String region, String csrfToken, Instant expiresAt, Instant authTime, StepUp stepUp,
        boolean organizationAdmin, Map<String, Allowed> decisions) {

    /** The same view with the account's console actions decided. */
    public SessionView withDecisions(Map<String, Decision> decided) {
        Map<String, Allowed> out = new LinkedHashMap<>();
        decided.forEach((key, d) -> out.put(key,
                new Allowed(d.allowed(), d.allowed() ? null : d.denial(), d.allowed() && d.elevated() ? Boolean.TRUE : null)));
        return new SessionView(user, account, region, csrfToken, expiresAt, authTime, stepUp, organizationAdmin, out);
    }

    /**
     * Whether the person may, and when not, the denial that says why and whether it can be asked
     * for; {@code elevated} is true when only a role held for a while allows it.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Allowed(boolean allowed, Denial denied, Boolean elevated) {
    }

    public record User(String userId, String email, String name, List<String> groups) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Account(String accountId, String name, String status, String defaultRegion, List<String> roles, List<String> groups) {
    }

    /** The guarded actions and the moment until which the current authentication satisfies them. */
    public record StepUp(List<String> actions, Instant freshUntil) {
    }
}
