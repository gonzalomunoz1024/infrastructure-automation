package com.iaf.console.bff.cost.application;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.bff.cost.domain.BudgetCheck;
import com.iaf.console.bff.cost.domain.BudgetRule;
import com.iaf.console.bff.cost.domain.Estimate;
import com.iaf.console.bff.cost.ports.outbound.CostPort;

import reactor.core.publisher.Mono;

/**
 * What the account's budgets say about a create, before its preview and before it runs: the cost
 * service's {@code budgets:check} rules ({@code budget.exceeded} denies, {@code budget.forecast-over}
 * warns), and its list-price estimate when the provider's plan prices nothing.
 *
 * <p>Both fail open: the cost service is advice on top of the platform's own policy, and a create
 * must not stop working because cost is down. When it does not answer within
 * {@code console.cost.check-timeout} (2s), cannot be reached or answers an error, the BFF logs it
 * and goes on with no budget rule and no estimate; nothing is denied for it.
 */
public class BudgetGuard {

    private static final Logger log = LoggerFactory.getLogger(BudgetGuard.class);

    private final CostPort cost;
    private final Duration within;

    public BudgetGuard(CostPort cost, Duration within) {
        this.cost = cost;
        this.within = within;
    }

    /** The budget rules for a create; none when the cost service is down or slow (fail open). */
    public Mono<List<BudgetRule>> check(BudgetCheck check) {
        return Mono.defer(() -> cost.checkBudgets(check, within))
                .onErrorResume(e -> {
                    // fail open: no budget rule rather than no create while the cost service is away
                    log.warn("budget check skipped for a {} create in {}: the cost service did not answer ({})", check.type(), check.accountId(), e.toString());
                    return Mono.just(List.of());
                });
    }

    /** The list-price estimate of {@code count} resources of a type; empty when unpriced or when the cost service is down or slow (fail open). */
    public Mono<Optional<Estimate>> estimate(String type, String region, Map<String, Object> attributes, int count) {
        return Mono.defer(() -> cost.estimate(type, region, attributes, count, within))
                .map(e -> e.priced() ? Optional.of(e) : Optional.<Estimate>empty())
                .onErrorResume(e -> {
                    // fail open: the preview shows no cost rather than failing
                    log.warn("cost estimate skipped for a {} create: the cost service did not answer ({})", type, e.toString());
                    return Mono.just(Optional.empty());
                });
    }
}
