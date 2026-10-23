package com.iaf.console.identity.elevations.adapters;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.iaf.console.identity.elevations.domain.ElevationRules;

/**
 * {@code console.identity.elevations}: how long an ask lasts when it names none
 * ({@code default-minutes}, 60) and at most ({@code max-minutes}, 480), the longest break-glass
 * access ({@code break-glass-max-minutes}, 240), how long an ask waits for a person
 * ({@code pending-for}, 24 hours), how often the expiry sweep runs ({@code sweep-every}, a minute),
 * and the rules that approve an ask at once ({@code auto-approval}, read in order).
 */
@ConfigurationProperties(prefix = "console.identity.elevations")
public record ElevationProperties(Integer defaultMinutes, Integer maxMinutes, Integer breakGlassMaxMinutes, Duration pendingFor, Duration sweepEvery,
        List<Rule> autoApproval) {

    /** One rule as written in the configuration. */
    public record Rule(String name, List<String> roles, List<String> requesterRoles, Integer maxMinutes) {
    }

    public ElevationProperties {
        sweepEvery = sweepEvery == null ? Duration.ofMinutes(1) : sweepEvery;
        if (sweepEvery.isNegative() || sweepEvery.isZero()) {
            throw new IllegalArgumentException("console.identity.elevations.sweep-every must be positive");
        }
    }

    public ElevationRules rules() {
        List<ElevationRules.AutoApproval> rules = autoApproval == null ? List.of()
                : autoApproval.stream()
                        .map(r -> new ElevationRules.AutoApproval(r.name(), r.roles(), r.requesterRoles(), r.maxMinutes() == null ? 0 : r.maxMinutes()))
                        .toList();
        return new ElevationRules(defaultMinutes == null ? 60 : defaultMinutes, maxMinutes == null ? ElevationRules.MAX_ASK_MINUTES : maxMinutes,
                pendingFor == null ? Duration.ofHours(24) : pendingFor,
                breakGlassMaxMinutes == null ? ElevationRules.MAX_BREAK_GLASS_MINUTES : breakGlassMaxMinutes, rules);
    }
}
