package com.iaf.console.identity.quotas.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A rule that approves a quota increase on the spot, as AWS approves some Service Quotas requests
 * at once: it covers the keys its patterns name ({@code *} is any run of characters), and approves
 * an ask of at most {@code maxFactor} times the current limit and never above {@code maxLimit},
 * once per {@code cooldown} for an account's key (any rule's approval of the key counts).
 */
public record AutoApprovalRule(String name, List<String> keys, double maxFactor, int maxLimit, Duration cooldown) {

    public AutoApprovalRule {
        Objects.requireNonNull(name, "name");
        keys = keys == null ? List.of() : List.copyOf(keys);
        if (name.isBlank() || keys.isEmpty() || maxFactor < 1 || maxLimit < 1) {
            throw new IllegalArgumentException("auto-approval rule '" + name + "' needs keys, a factor of at least 1 and a maximum of at least 1");
        }
        cooldown = cooldown == null ? Duration.ZERO : cooldown;
    }

    public boolean covers(String key) {
        for (String pattern : keys) {
            if (glob(pattern).matcher(key).matches()) {
                return true;
            }
        }
        return false;
    }

    /** The highest limit it approves from the current one: the factor's, capped by the rule's maximum. */
    public int ceiling(int current) {
        return (int) Math.min(maxLimit, Math.floor(current * maxFactor));
    }

    /** Whether it raises {@code current} to {@code requested} at {@code now}, a rule having last approved the key at {@code lastApproval} (null for never). */
    public boolean approves(String key, int current, int requested, Instant lastApproval, Instant now) {
        return covers(key) && requested <= ceiling(current) && (lastApproval == null || !lastApproval.plus(cooldown).isAfter(now));
    }

    private static Pattern glob(String pattern) {
        StringBuilder regex = new StringBuilder();
        for (String literal : pattern.split("\\*", -1)) {
            if (!regex.isEmpty()) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(literal));
        }
        return Pattern.compile(regex.toString());
    }
}
