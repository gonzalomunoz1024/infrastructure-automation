package com.iaf.console.identity.quotas.adapters;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.iaf.console.identity.quotas.domain.AutoApprovalRule;
import com.iaf.console.identity.quotas.domain.QuotaRules;

/** {@code console.identity.quotas}: who decides an increase, and the rules that approve one at once. */
@ConfigurationProperties(prefix = "console.identity.quotas")
public record QuotaRulesProperties(String approver, List<Rule> autoApproval) {

    /** One rule as written in the configuration; a missing factor is 1 (no increase), a missing cooldown none. */
    public record Rule(String name, List<String> keys, Double maxFactor, Integer maxLimit, Duration cooldown) {
    }

    public QuotaRules rules() {
        List<AutoApprovalRule> rules = autoApproval == null ? List.of()
                : autoApproval.stream()
                        .map(r -> new AutoApprovalRule(r.name(), r.keys(), r.maxFactor() == null ? 1 : r.maxFactor(), r.maxLimit() == null ? 0 : r.maxLimit(),
                                r.cooldown()))
                        .toList();
        return new QuotaRules(rules, approver);
    }
}
