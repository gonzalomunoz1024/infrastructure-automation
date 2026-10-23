package com.iaf.console.bff.session.application;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** The {@code Session} the web app reads: who, where, the CSRF token, and the step-up state. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionView(User user, Account account, String region, String csrfToken, Instant expiresAt, Instant authTime, StepUp stepUp) {

    public record User(String userId, String email, String name, List<String> groups) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Account(String accountId, String name, String status, String defaultRegion, List<String> roles, List<String> groups) {
    }

    /** The guarded actions and the moment until which the current authentication satisfies them. */
    public record StepUp(List<String> actions, Instant freshUntil) {
    }
}
