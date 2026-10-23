package com.iaf.console.bff.resources.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.iaf.console.bff.common.relay.RelayCall;
import com.iaf.console.bff.common.relay.Relayed;
import com.iaf.console.bff.cost.CostFixtures;
import com.iaf.console.bff.cost.application.BudgetGuard;
import com.iaf.console.bff.cost.domain.BudgetCheck;
import com.iaf.console.bff.cost.domain.BudgetRule;
import com.iaf.console.bff.cost.domain.Estimate;
import com.iaf.console.bff.cost.domain.Money;
import com.iaf.console.bff.cost.ports.outbound.CostPort;
import com.iaf.console.bff.resources.domain.BudgetExceededException;
import com.iaf.console.bff.resources.domain.ConsolePolicy;
import com.iaf.console.bff.resources.domain.PreviewView;
import com.iaf.console.bff.resources.domain.StateCaller;
import com.iaf.console.bff.types.domain.ConsoleType;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.platform.model.Cost;

import reactor.core.publisher.Mono;

/**
 * The budgets on a create: their rules join the preview with their budget, a deny refuses the
 * create, and a preview the plan priced nothing is priced from the cost service's estimate, whose
 * monthly cost the budget check then carries.
 */
class CreateBudgetsTest {

    private static final String BUDGET = "bud_01jabcdefghjkmnpqrstvwxyz0";
    private static final ConsoleType VM = new ConsoleType("compute/vm", "vm", "compute", "vms", "vm", "box", null, null, null, null, null, null, null,
            null, null);
    private static final BudgetRule DENY = new BudgetRule(BudgetRule.EXCEEDED, "deny", "The budget Compute is over", BUDGET, "Compute");
    private static final BudgetRule WARN = new BudgetRule(BudgetRule.FORECAST_OVER, "warn", "The budget Compute is forecast over", BUDGET, "Compute");

    /** A cost service that answers the rules and estimate given, and records the checks. */
    static class FakeCost implements CostPort {
        final List<BudgetCheck> checks = new ArrayList<>();
        int estimates;
        private final List<BudgetRule> rules;
        private final Estimate estimate;

        FakeCost(List<BudgetRule> rules, Estimate estimate) {
            this.rules = rules;
            this.estimate = estimate;
        }

        @Override
        public Mono<Relayed> callAsPerson(AccountScope scope, StateCaller caller, RelayCall call) {
            return Mono.error(new UnsupportedOperationException());
        }

        @Override
        public Mono<Relayed> callAsService(RelayCall call) {
            return Mono.error(new UnsupportedOperationException());
        }

        @Override
        public Mono<List<BudgetRule>> checkBudgets(BudgetCheck check, Duration within) {
            checks.add(check);
            return Mono.just(rules);
        }

        @Override
        public Mono<Estimate> estimate(String type, String region, Map<String, Object> attributes, int count, Duration within) {
            estimates++;
            return estimate == null ? Mono.error(new IllegalStateException("down")) : Mono.just(estimate);
        }
    }

    private static PreviewView preview(Cost cost) {
        return new PreviewView("create-vm", "2.0.1", List.of(), List.of(), cost, null, null);
    }

    @Test
    void aPreviewThePlanPricedNothingIsPricedFromTheEstimateAndCheckedWithIt() {
        FakeCost cost = new FakeCost(List.of(WARN), new Estimate(new Money(new BigDecimal("77.0"), "USD"), "cpu 4 at $77.00 a month (list prices)", true));
        CreateBudgets budgets = new CreateBudgets(new BudgetGuard(cost, Duration.ofSeconds(2)));
        PreviewView view = budgets.preview(CostFixtures.session(), VM, Map.of("cpu", 4), Map.of("owner", "team-compute"), preview(null)).block();

        assertEquals(77.0, view.cost().monthly());
        assertEquals("USD", view.cost().currency());
        assertEquals("cpu 4 at $77.00 a month (list prices)", view.cost().detail());
        assertEquals(new BigDecimal("77.0"), cost.checks.get(0).monthly(), "the check carries the estimate");
        assertEquals("team-compute", cost.checks.get(0).tags().get("owner"));
        assertEquals("allow", view.policy().decision(), "a warning does not deny");
        ConsolePolicy.Rule rule = view.policy().rules().get(0);
        assertEquals(BudgetRule.FORECAST_OVER, rule.id());
        assertEquals(BUDGET, rule.budgetId());
    }

    @Test
    void aPricedPreviewKeepsItsCostAndADenyRuleDeniesIt() {
        FakeCost cost = new FakeCost(List.of(DENY), null);
        CreateBudgets budgets = new CreateBudgets(new BudgetGuard(cost, Duration.ofSeconds(2)));
        PreviewView view = budgets.preview(CostFixtures.session(), VM, Map.of(), Map.of(), preview(new Cost(38.5, "USD", "vsphere vm.create 38.5"))).block();

        assertEquals(0, cost.estimates, "no estimate when the plan priced it");
        assertEquals(new BigDecimal("38.5"), cost.checks.get(0).monthly());
        assertEquals(38.5, view.cost().monthly());
        assertEquals("deny", view.policy().decision());
        assertEquals(BUDGET, view.policy().rules().get(0).budgetId());
    }

    @Test
    void aDenyRefusesTheCreateAndAWarningDoesNot() {
        CreateBudgets denying = new CreateBudgets(new BudgetGuard(new FakeCost(List.of(DENY, WARN), null), Duration.ofSeconds(2)));
        BudgetExceededException refused = assertThrows(BudgetExceededException.class,
                () -> denying.require(CostFixtures.session(), VM, Map.of()).block());
        assertEquals("budget.exceeded", refused.code());
        assertEquals("The budget Compute is over", refused.getMessage());
        assertEquals(List.of(Map.of("budgetId", BUDGET, "name", "Compute", "message", "The budget Compute is over")), refused.detail().get("budgets"));
        assertThrows(BudgetExceededException.class, () -> denying.requireRows(CostFixtures.session(), VM, List.of(Map.of(), Map.of("a", "b"))).block());

        CreateBudgets warning = new CreateBudgets(new BudgetGuard(new FakeCost(List.of(WARN), null), Duration.ofSeconds(2)));
        assertNull(warning.require(CostFixtures.session(), VM, Map.of()).block());
    }

    @Test
    void withTheCostServiceDownThePreviewIsUnchanged() {
        CostPort down = new FakeCost(List.of(), null) {
            @Override
            public Mono<List<BudgetRule>> checkBudgets(BudgetCheck check, Duration within) {
                return Mono.error(new java.util.concurrent.TimeoutException("slow"));
            }
        };
        CreateBudgets budgets = new CreateBudgets(new BudgetGuard(down, Duration.ofSeconds(2)));
        PreviewView view = budgets.preview(CostFixtures.session(), VM, Map.of(), Map.of(), preview(null)).block();
        assertNull(view.cost());
        assertEquals("allow", view.policy().decision());
        assertEquals(List.of(), view.policy().rules());
        assertNull(budgets.require(CostFixtures.session(), VM, Map.of()).block(), "fail open: nothing is refused");
    }

    @Test
    void aBatchPreviewNamesEachBudgetOnce() {
        ConsolePolicy policy = CreateBudgets.withBudgetRules(new ConsolePolicy("allow", List.of(), "console"), List.of(List.of(WARN), List.of(WARN)));
        assertEquals(1, policy.rules().size());
        assertEquals(BUDGET, policy.rules().get(0).budgetId());
        assertEquals(Optional.empty(), Optional.ofNullable(CreateBudgets.withBudget(preview(null), Optional.empty(), List.of()).cost()));
    }
}
