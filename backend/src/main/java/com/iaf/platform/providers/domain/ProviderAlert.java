package com.iaf.platform.providers.domain;

import java.time.Instant;

/**
 * One alert the evaluator raised: the health band got worse, or a single call failed with a class
 * other than not_found. {@code severity} is warning or critical; {@code action} and {@code jobId}
 * are set when the alert is about one call; {@code channel} is the provider's {@code alerts.channel},
 * where the alert is meant to be delivered, or null when the provider names none.
 */
public record ProviderAlert(
        String id,
        Instant at,
        String provider,
        String action,
        String message,
        String severity,
        String jobId,
        String channel) {

    public static final String WARNING = "warning";
    public static final String CRITICAL = "critical";
}
