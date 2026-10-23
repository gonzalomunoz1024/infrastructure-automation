package com.iaf.console.drift.drift.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * The plan's tiers: hot (changed in the last 24 hours, in production, or drifted lately) every 15
 * minutes, warm on the type's interval (hourly when it names none), cold (untouched for 30 days
 * and never drifted) daily. A resource with an open finding is hot, so a revert shows soon.
 */
public record TierRules(Duration hot, Duration warm, Duration cold, Duration recentChange, Duration driftedFor, Duration coldAfter) {

    public static TierRules defaults() {
        return new TierRules(Duration.ofMinutes(15), Duration.ofHours(1), Duration.ofHours(24), Duration.ofHours(24), Duration.ofDays(7),
                Duration.ofDays(30));
    }

    public TierRules {
        hot = hot == null ? Duration.ofMinutes(15) : hot;
        warm = warm == null ? Duration.ofHours(1) : warm;
        cold = cold == null ? Duration.ofHours(24) : cold;
        recentChange = recentChange == null ? Duration.ofHours(24) : recentChange;
        driftedFor = driftedFor == null ? Duration.ofDays(7) : driftedFor;
        coldAfter = coldAfter == null ? Duration.ofDays(30) : coldAfter;
    }

    public Tier tierOf(Watch w, Instant now) {
        if (w.openFindingId() != null || w.production()) {
            return Tier.HOT;
        }
        if (w.resourceUpdatedAt() != null && w.resourceUpdatedAt().isAfter(now.minus(recentChange))) {
            return Tier.HOT;
        }
        if (w.lastDriftAt() != null && w.lastDriftAt().isAfter(now.minus(driftedFor))) {
            return Tier.HOT;
        }
        if (w.lastDriftAt() == null && w.resourceUpdatedAt() != null && w.resourceUpdatedAt().isBefore(now.minus(coldAfter))) {
            return Tier.COLD;
        }
        return Tier.WARM;
    }

    /** The interval of a tier; warm takes the type's own interval when it has one. */
    public Duration intervalOf(Tier tier, Duration typeInterval) {
        return switch (tier) {
            case HOT -> hot;
            case WARM -> typeInterval != null && !typeInterval.isZero() ? typeInterval : warm;
            case COLD -> cold;
        };
    }

    /**
     * When a resource is next due: between 80% and 90% of its tier's interval from now, spread by
     * its wfrn so a shard's reads do not land together. With its loop waking at a fifteenth of the
     * interval, a resource is always read again within its tier's interval (the plan's guarantee:
     * a change shows within 15 minutes on the hot tier).
     */
    public Instant nextCheck(Watch w, Tier tier, Duration typeInterval, Instant now) {
        long interval = intervalOf(tier, typeInterval).toMillis();
        long spread = Math.max(1, interval / 10);
        return now.plusMillis(interval * 8 / 10 + Math.floorMod(w.wfrn().hashCode(), spread));
    }

    /** The type's {@code observe.interval}: {@code 15m}, {@code 1h}, {@code 2d}, or an ISO duration; null when absent or unreadable. */
    public static Duration parseInterval(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String v = s.trim().toLowerCase(Locale.ROOT);
        try {
            if (v.startsWith("pt") || v.startsWith("p")) {
                return Duration.parse(v.toUpperCase(Locale.ROOT));
            }
            char unit = v.charAt(v.length() - 1);
            long n = Long.parseLong(v.substring(0, v.length() - 1).trim());
            return switch (unit) {
                case 's' -> Duration.ofSeconds(n);
                case 'm' -> Duration.ofMinutes(n);
                case 'h' -> Duration.ofHours(n);
                case 'd' -> Duration.ofDays(n);
                default -> null;
            };
        } catch (RuntimeException e) {
            return null;
        }
    }
}
