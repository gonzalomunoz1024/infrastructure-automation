package com.iaf.console.identity.elevations.application;

/** The optional body of {@code POST .../elevations/{elevationId}:revoke}. */
public record RevokeElevation(String comment) {
}
