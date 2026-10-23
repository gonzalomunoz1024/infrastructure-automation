package com.iaf.console.cost.budgets.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.iaf.console.cost.pricing.domain.Micros;
import com.iaf.console.kit.errors.InvalidInputException;

/** A budget's state, its thresholds and the checks of its input. */
class BudgetRulesTest {

    @Test
    void thresholdsAreCrossedOnceEachLowestFirst() {
        long amount = Micros.of(100.0);
        assertThat(BudgetRules.crossed(List.of(100, 50, 80), List.of(), Micros.of(85.0), amount)).containsExactly(50, 80);
        assertThat(BudgetRules.crossed(List.of(50, 80, 100), List.of(50, 80), Micros.of(100.0), amount)).containsExactly(100);
        assertThat(BudgetRules.crossed(List.of(50, 80, 100), List.of(50, 80, 100), Micros.of(300.0), amount)).isEmpty();
    }

    @Test
    void theStateIsOverForecastOverOrOk() {
        long amount = Micros.of(100.0);
        assertThat(BudgetRules.state(amount, Micros.of(100.0), Micros.of(100.0))).isEqualTo(BudgetStatus.OVER);
        assertThat(BudgetRules.state(amount, Micros.of(60.0), Micros.of(120.0))).isEqualTo(BudgetStatus.FORECAST_OVER);
        assertThat(BudgetRules.state(amount, Micros.of(60.0), Micros.of(90.0))).isEqualTo(BudgetStatus.OK);
        assertThat(BudgetRules.percent(Micros.of(33.71), amount)).isEqualTo(33.71);
    }

    @Test
    void anInputGetsItsDefaultsAndEveryProblemAtOnce() {
        BudgetInput.Valid v = new BudgetInput(" Compute ", null, new BigDecimal("1000"), null, null, null).validate("USD");
        assertThat(v.name()).isEqualTo("Compute");
        assertThat(v.thresholds()).containsExactly(80, 100);
        assertThat(v.currency()).isEqualTo("USD");
        assertThat(v.blockCreates()).isFalse();

        assertThatThrownBy(() -> new BudgetInput("", null, BigDecimal.ZERO, "EUR", List.of(0, 0), true).validate("USD"))
                .isInstanceOfSatisfying(InvalidInputException.class, e -> assertThat(e.problems()).extracting(p -> p.field()).containsExactly("/name", "/amount", "/currency", "/thresholds", "/thresholds"));
    }

    @Test
    void aTagScopeCoversOnlyResourcesWithTheValue() {
        BudgetScope s = new BudgetScope("123456789012", new BudgetScope.Tag("team", "web"));
        assertThat(s.covers(java.util.Map.of("team", "web"))).isTrue();
        assertThat(s.covers(java.util.Map.of("team", "data"))).isFalse();
        assertThat(new BudgetScope("123456789012", null).covers(null)).isTrue();
    }
}
