package com.iaf.console.identity.elevations.application;

/** The body of {@code POST /v1/accounts/{accountId}/break-glass}. */
public record OpenBreakGlass(String userId, String role, String reason, Integer durationMinutes, String approvedBy, String ticket) {
}
