package com.iaf.console.identity.elevations.application;

/** The body of {@code POST /v1/accounts/{accountId}/elevations}. */
public record RequestElevation(String role, String reason, Integer durationMinutes) {
}
