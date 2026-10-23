package com.iaf.console.bff.resources.domain;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.iaf.console.platform.model.PolicyDecision;
import com.iaf.console.platform.model.PolicyRule;

/**
 * A preview's policy decision as the console answers it: the platform's rules, then the console's
 * own ({@code tags.required}, {@code dependents.exist}, the budget rules). A budget rule names its
 * budget in {@code budgetId}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConsolePolicy(String decision, List<Rule> rules, String engine) {

    public static final String ALLOW = "allow";
    public static final String DENY = "deny";

    /** One rule: its id, its result (allow, warn, deny), the sentence, and the budget it is about, if any. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Rule(String id, String result, String message, String budgetId) {

        public static Rule of(String id, String result, String message) {
            return new Rule(id, result, message, null);
        }
    }

    public ConsolePolicy {
        decision = decision == null ? ALLOW : decision;
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    /** The platform's decision, as the console carries it on. */
    public static ConsolePolicy of(PolicyDecision platform) {
        if (platform == null) {
            return new ConsolePolicy(ALLOW, List.of(), null);
        }
        List<Rule> rules = new ArrayList<>();
        if (platform.rules() != null) {
            for (PolicyRule r : platform.rules()) {
                rules.add(Rule.of(r.id(), r.result(), r.message()));
            }
        }
        return new ConsolePolicy(platform.decision(), rules, platform.engine());
    }

    /** With one more rule: a deny makes the decision a deny. */
    public ConsolePolicy with(Rule rule) {
        List<Rule> more = new ArrayList<>(rules);
        more.add(rule);
        return new ConsolePolicy(DENY.equals(rule.result()) ? DENY : decision, more, engine);
    }
}
