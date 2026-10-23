package com.iaf.console.bff.resources.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.iaf.console.bff.cost.application.BudgetGuard;
import com.iaf.console.bff.cost.domain.BudgetCheck;
import com.iaf.console.bff.cost.domain.BudgetRule;
import com.iaf.console.bff.cost.domain.Estimate;
import com.iaf.console.bff.resources.domain.BudgetExceededException;
import com.iaf.console.bff.resources.domain.ConsolePolicy;
import com.iaf.console.bff.resources.domain.PreviewView;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.bff.types.domain.ConsoleType;
import com.iaf.console.platform.model.Cost;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The account's budgets on a create (decision 25), before its preview and before it runs: the cost
 * service's {@code budgets:check} with the create's account, type, service and tags. Its rules join
 * the preview's policy with their {@code budgetId} ({@code budget.exceeded} a deny,
 * {@code budget.forecast-over} a warning), and a deny refuses the create itself (409
 * {@code budget.exceeded}), as the console's other deny rules do. A preview the provider's plan
 * priced nothing is priced from the cost service's estimate, and the check carries that monthly
 * cost; a create carries 0, as only a deny refuses it and a deny does not depend on it. When the
 * cost service is down or slow nothing is denied (the {@link BudgetGuard} fails open).
 */
public class CreateBudgets {

    private static final int CONCURRENCY = 4;

    private final BudgetGuard guard;

    public CreateBudgets(BudgetGuard guard) {
        this.guard = guard;
    }

    /** No budgets: what a test that builds the resources by hand passes. */
    public static CreateBudgets none() {
        return new CreateBudgets(null) {
            @Override
            public Mono<PreviewView> preview(Session session, ConsoleType type, Map<String, Object> input, Map<String, String> tags, PreviewView view) {
                return Mono.just(view);
            }

            @Override
            public Mono<List<BudgetRule>> rules(Session session, ConsoleType type, Map<String, String> tags) {
                return Mono.just(List.of());
            }
        };
    }

    /** The create's preview with its budget rules, and the estimate as its cost when the plan priced nothing. */
    public Mono<PreviewView> preview(Session session, ConsoleType type, Map<String, Object> input, Map<String, String> tags, PreviewView view) {
        Mono<Optional<Estimate>> estimate = view.cost() != null ? Mono.just(Optional.empty())
                : guard.estimate(type.type(), session.region(), input, 1);
        return estimate.flatMap(e -> {
            BigDecimal monthly = view.cost() != null ? BigDecimal.valueOf(view.cost().monthly())
                    : e.map(x -> x.monthly().amount()).orElse(BigDecimal.ZERO);
            return guard.check(new BudgetCheck(session.accountId(), type.type(), type.service(), tags, monthly))
                    .map(rules -> withBudget(view, e, rules));
        });
    }

    /** The budget rules for a create of the type with these tags (monthly 0). */
    public Mono<List<BudgetRule>> rules(Session session, ConsoleType type, Map<String, String> tags) {
        return guard.check(new BudgetCheck(session.accountId(), type.type(), type.service(), tags, BigDecimal.ZERO));
    }

    /** Completes when no budget refuses the create; 409 {@code budget.exceeded} otherwise. */
    public Mono<Void> require(Session session, ConsoleType type, Map<String, String> tags) {
        return rules(session, type, tags).flatMap(rules -> {
            List<Map<String, Object>> denying = denying(rules);
            return denying.isEmpty() ? Mono.<Void>empty() : Mono.<Void>error(BudgetExceededException.of(denying));
        });
    }

    /** Each row's budget rules, in the rows' order, one check per distinct set of tags. */
    public Mono<List<List<BudgetRule>>> rowRules(Session session, ConsoleType type, List<Map<String, String>> rowTags) {
        List<Map<String, String>> distinct = rowTags.stream().distinct().toList();
        return Flux.fromIterable(distinct).flatMapSequential(tags -> rules(session, type, tags), CONCURRENCY).collectList().map(answers -> {
            List<List<BudgetRule>> out = new ArrayList<>();
            for (Map<String, String> tags : rowTags) {
                out.add(answers.get(distinct.indexOf(tags)));
            }
            return out;
        });
    }

    /** Completes when no budget refuses any row of a batch create; 409 {@code budget.exceeded} otherwise. */
    public Mono<Void> requireRows(Session session, ConsoleType type, List<Map<String, String>> rowTags) {
        return rowRules(session, type, rowTags).flatMap(all -> {
            List<Map<String, Object>> denying = new ArrayList<>();
            for (List<BudgetRule> rules : all) {
                for (Map<String, Object> d : denying(rules)) {
                    if (!denying.contains(d)) {
                        denying.add(d);
                    }
                }
            }
            return denying.isEmpty() ? Mono.<Void>empty() : Mono.<Void>error(BudgetExceededException.of(denying));
        });
    }

    /** The preview with the estimate as its cost (when it had none) and every budget rule with its budget. */
    static PreviewView withBudget(PreviewView view, Optional<Estimate> estimate, List<BudgetRule> rules) {
        PreviewView out = view;
        if (out.cost() == null && estimate.isPresent()) {
            Estimate e = estimate.get();
            out = out.withCost(new Cost(e.monthly().asDouble(), e.monthly().currency(), e.detail() == null ? "list prices" : e.detail()));
        }
        for (BudgetRule rule : rules) {
            out = out.withRule(new ConsolePolicy.Rule(rule.id(), rule.result(), rule.message(), rule.budgetId()));
        }
        return out;
    }

    /** A batch preview's policy with each distinct budget rule of its rows. */
    static ConsolePolicy withBudgetRules(ConsolePolicy policy, List<List<BudgetRule>> rowRules) {
        ConsolePolicy out = policy;
        List<String> seen = new ArrayList<>();
        for (List<BudgetRule> rules : rowRules) {
            for (BudgetRule rule : rules) {
                String key = rule.id() + "|" + rule.budgetId();
                if (!seen.contains(key)) {
                    seen.add(key);
                    out = out.with(new ConsolePolicy.Rule(rule.id(), rule.result(), rule.message(), rule.budgetId()));
                }
            }
        }
        return out;
    }

    private static List<Map<String, Object>> denying(List<BudgetRule> rules) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (BudgetRule rule : rules) {
            if (rule.denies()) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("budgetId", rule.budgetId());
                if (rule.budgetName() != null) {
                    d.put("name", rule.budgetName());
                }
                if (rule.message() != null) {
                    d.put("message", rule.message());
                }
                out.add(d);
            }
        }
        return out;
    }
}
