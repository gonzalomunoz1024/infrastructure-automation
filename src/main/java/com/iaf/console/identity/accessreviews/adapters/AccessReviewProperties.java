package com.iaf.console.identity.accessreviews.adapters;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The access reviews' settings, under {@code console.identity.access-reviews}: how often the sweep
 * runs ({@code sweep-every}, 15 minutes), and the scheduled campaigns it starts
 * ({@code scheduled.enabled}, on; {@code scheduled.cadence}, 90 days after an account's last
 * campaign started or, with none, after the account was created; {@code scheduled.due-in-days},
 * 14).
 */
@ConfigurationProperties(prefix = "console.identity.access-reviews")
public record AccessReviewProperties(Duration sweepEvery, Scheduled scheduled) {

    public AccessReviewProperties {
        sweepEvery = sweepEvery == null ? Duration.ofMinutes(15) : sweepEvery;
        scheduled = scheduled == null ? new Scheduled(null, null, null) : scheduled;
        if (sweepEvery.isNegative() || sweepEvery.isZero()) {
            throw new IllegalArgumentException("console.identity.access-reviews.sweep-every must be positive");
        }
    }

    /** The quarterly campaigns: whether the sweep starts them, how long after the last one, and how long each stays open. */
    public record Scheduled(Boolean enabled, Duration cadence, Integer dueInDays) {

        public Scheduled {
            enabled = enabled == null ? Boolean.TRUE : enabled;
            cadence = cadence == null ? Duration.ofDays(90) : cadence;
            dueInDays = dueInDays == null ? 14 : dueInDays;
            if (cadence.isNegative() || cadence.isZero()) {
                throw new IllegalArgumentException("console.identity.access-reviews.scheduled.cadence must be positive");
            }
            if (dueInDays < 1 || dueInDays > 90) {
                throw new IllegalArgumentException("console.identity.access-reviews.scheduled.due-in-days is 1 to 90");
            }
        }
    }
}
