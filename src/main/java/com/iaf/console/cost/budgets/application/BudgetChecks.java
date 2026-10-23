package com.iaf.console.cost.budgets.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import com.iaf.console.cost.budgets.domain.Budget;
import com.iaf.console.cost.budgets.domain.BudgetCheck;
import com.iaf.console.cost.budgets.domain.BudgetPeriod;
import com.iaf.console.cost.budgets.domain.BudgetRules;
import com.iaf.console.cost.budgets.ports.outbound.BudgetStorePort;
import com.iaf.console.cost.ledger.domain.Forecasts;
import com.iaf.console.cost.pricing.domain.Estimate;
import com.iaf.console.cost.pricing.domain.Micros;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Whether a create may go ahead: every budget of the account whose scope covers the new
 * resources' tags. Over this month with {@code blockCreates}: a deny. Its forecast plus what the
 * new resources cost for the rest of the month over the amount: a warning.
 */
public final class BudgetChecks {

    private final BudgetStorePort budgets;
    private final BudgetsService service;
    private final Clock clock;

    public BudgetChecks(BudgetStorePort budgets, BudgetsService service, Clock clock) {
        this.budgets = budgets;
        this.service = service;
        this.clock = clock;
    }

    public Mono<BudgetCheck> check(AccountScope scope, BudgetCheck.Request request) {
        long added = request.monthly() == null ? 0 : Math.round(Micros.of(request.monthly()) * Forecasts.remainingShare(clock.instant()));
        return budgets.listAll(scope).filter(b -> b.scope().covers(request.tags()))
                .concatMap(b -> service.current(scope, b).map(p -> rules(b, p, added)))
                .flatMapIterable(r -> r).collectList().map(BudgetCheck::of);
    }

    static List<BudgetCheck.Rule> rules(Budget b, BudgetPeriod p, long addedMicros) {
        List<BudgetCheck.Rule> out = new ArrayList<>();
        String amount = Estimate.money(Micros.toAmount(p.amountMicros()), b.currency());
        if (b.blockCreates() && p.spentMicros() >= p.amountMicros()) {
            out.add(new BudgetCheck.Rule(BudgetCheck.EXCEEDED, "deny",
                    "The budget " + b.name() + " is over: " + Estimate.money(Micros.toAmount(p.spentMicros()), b.currency()) + " of " + amount
                            + " spent in " + p.period() + ", so creates in its scope are refused",
                    b.budgetId(), b.name(), BudgetRules.percent(p.spentMicros(), p.amountMicros())));
            return out;
        }
        long forecast = p.forecastMicros() + addedMicros;
        if (forecast > p.amountMicros()) {
            String with = addedMicros > 0 ? " with this create" : "";
            out.add(new BudgetCheck.Rule(BudgetCheck.FORECAST_OVER, "warn",
                    "The budget " + b.name() + " is forecast to reach " + Estimate.money(Micros.toAmount(forecast), b.currency()) + " of " + amount
                            + " in " + p.period() + with,
                    b.budgetId(), b.name(), BudgetRules.percent(forecast, p.amountMicros())));
        }
        return out;
    }
}
