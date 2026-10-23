package com.iaf.console.identity.elevations.domain;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The organization's elevation settings: how long an ask lasts when it names no duration and at
 * most, how long one waits for a person, the longest break-glass access, and the rules that
 * approve an ask at once, read in order.
 */
public record ElevationRules(int defaultMinutes, int maxMinutes, Duration pendingFor, int breakGlassMaxMinutes, List<AutoApproval> autoApproval) {

    /** The bounds the contract sets on any duration: at least 5 minutes, an ask at most 8 hours, break-glass access at most 4. */
    public static final int MIN_MINUTES = 5;
    public static final int MAX_ASK_MINUTES = 480;
    public static final int MAX_BREAK_GLASS_MINUTES = 240;

    /**
     * A rule: it approves an ask for one of {@code roles} by a person who holds one of
     * {@code requesterRoles} through a group of the account, for at most {@code maxMinutes}.
     */
    public record AutoApproval(String name, List<String> roles, List<String> requesterRoles, int maxMinutes) {

        public AutoApproval {
            Objects.requireNonNull(name, "name");
            roles = roles == null ? List.of() : List.copyOf(roles);
            requesterRoles = requesterRoles == null ? List.of() : List.copyOf(requesterRoles);
            if (roles.isEmpty() || requesterRoles.isEmpty()) {
                throw new IllegalArgumentException("the elevation rule " + name + " names no roles or no requester roles");
            }
            if (maxMinutes < MIN_MINUTES || maxMinutes > MAX_ASK_MINUTES) {
                throw new IllegalArgumentException("the elevation rule " + name + " approves 5 to 480 minutes");
            }
        }

        public boolean approves(String role, Collection<String> heldRoles, int minutes) {
            return roles.contains(role) && minutes <= maxMinutes && heldRoles.stream().anyMatch(requesterRoles::contains);
        }
    }

    public ElevationRules {
        Objects.requireNonNull(pendingFor, "pendingFor");
        autoApproval = autoApproval == null ? List.of() : List.copyOf(autoApproval);
        if (maxMinutes < MIN_MINUTES || maxMinutes > MAX_ASK_MINUTES) {
            throw new IllegalArgumentException("console.identity.elevations.max-minutes is 5 to 480");
        }
        if (defaultMinutes < MIN_MINUTES || defaultMinutes > maxMinutes) {
            throw new IllegalArgumentException("console.identity.elevations.default-minutes is 5 to max-minutes");
        }
        if (breakGlassMaxMinutes < MIN_MINUTES || breakGlassMaxMinutes > MAX_BREAK_GLASS_MINUTES) {
            throw new IllegalArgumentException("console.identity.elevations.break-glass-max-minutes is 5 to 240");
        }
        if (pendingFor.isNegative() || pendingFor.isZero()) {
            throw new IllegalArgumentException("console.identity.elevations.pending-for must be positive");
        }
    }

    /** The first rule that approves an ask for {@code role} of {@code minutes} by a person holding {@code heldRoles}. */
    public Optional<AutoApproval> approving(String role, Collection<String> heldRoles, int minutes) {
        return autoApproval.stream().filter(rule -> rule.approves(role, heldRoles, minutes)).findFirst();
    }
}
