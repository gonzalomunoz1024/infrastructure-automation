package com.iaf.console.identity.quotas.domain;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The organization's quota rules: the auto-approval rules, read in order, and who decides an
 * increase none of them approves (the platform team by default, with the internal {@code :decide}).
 */
public record QuotaRules(List<AutoApprovalRule> autoApproval, String approver) {

    public static final String PLATFORM_TEAM = "platform-team";

    public QuotaRules {
        autoApproval = autoApproval == null ? List.of() : List.copyOf(autoApproval);
        approver = approver == null || approver.isBlank() ? PLATFORM_TEAM : approver;
        Set<String> names = new HashSet<>();
        for (AutoApprovalRule rule : autoApproval) {
            if (!names.add(rule.name())) {
                throw new IllegalArgumentException("two auto-approval rules are named '" + rule.name() + "'");
            }
        }
    }

    /** The first rule that approves the ask, if one does. */
    public Optional<AutoApprovalRule> approving(String key, int current, int requested, Instant lastApproval, Instant now) {
        return autoApproval.stream().filter(rule -> rule.approves(key, current, requested, lastApproval, now)).findFirst();
    }
}
