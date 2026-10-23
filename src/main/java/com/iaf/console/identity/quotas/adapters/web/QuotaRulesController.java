package com.iaf.console.identity.quotas.adapters.web;

import java.time.Duration;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.iaf.console.identity.common.auth.Caller;
import com.iaf.console.identity.quotas.domain.AutoApprovalRule;
import com.iaf.console.identity.quotas.domain.QuotaRules;

import reactor.core.publisher.Mono;

/** {@code GET /v1/quota-rules}: the organization's auto-approval rules and who decides the rest, for anyone signed in. */
@RestController
public class QuotaRulesController {

    /** A rule as served: the cooldown as an ISO 8601 duration. */
    public record RuleView(String name, List<String> keys, double maxFactor, int maxLimit, String cooldown) {

        static RuleView of(AutoApprovalRule rule) {
            return new RuleView(rule.name(), rule.keys(), rule.maxFactor(), rule.maxLimit(), rule.cooldown().equals(Duration.ZERO) ? null
                    : rule.cooldown().toString());
        }
    }

    public record RulesView(List<RuleView> items, String approver) {
    }

    private final QuotaRules rules;

    public QuotaRulesController(QuotaRules rules) {
        this.rules = rules;
    }

    @GetMapping("/v1/quota-rules")
    public Mono<RulesView> rules(Caller caller) {
        return Mono.just(new RulesView(rules.autoApproval().stream().map(RuleView::of).toList(), rules.approver()));
    }
}
