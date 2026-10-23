package com.iaf.console.identity.elevations.application;

/** The body of {@code POST .../elevations/{elevationId}:decide}: {@code approve} or {@code reject}. */
public record DecideElevation(String decision, String comment) {
}
