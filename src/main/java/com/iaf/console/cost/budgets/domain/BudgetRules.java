package com.iaf.console.cost.budgets.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import com.iaf.console.cost.pricing.domain.Micros;

/**
 * The pure rules of a budget: its state from spent and forecast against the amount, the
 * thresholds a spend crosses that were not alerted yet this month, and the API's view of a period.
 */
public final class BudgetRules {

    private BudgetRules() {
    }

    public static String state(long amountMicros, long spentMicros, long forecastMicros) {
        if (spentMicros >= amountMicros) {
            return BudgetStatus.OVER;
        }
        return forecastMicros > amountMicros ? BudgetStatus.FORECAST_OVER : BudgetStatus.OK;
    }

    /** spent / amount × 100, to two decimals. */
    public static double percent(long partMicros, long amountMicros) {
        if (amountMicros <= 0) {
            return 0;
        }
        return BigDecimal.valueOf(partMicros).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(amountMicros), 2, RoundingMode.HALF_EVEN)
                .doubleValue();
    }

    /** The thresholds the spend reaches that were not alerted this period, lowest first. */
    public static List<Integer> crossed(List<Integer> thresholds, List<Integer> alreadySent, long spentMicros, long amountMicros) {
        return thresholds.stream().sorted()
                .filter(t -> !alreadySent.contains(t))
                .filter(t -> BigDecimal.valueOf(spentMicros).multiply(BigDecimal.valueOf(100))
                        .compareTo(BigDecimal.valueOf(amountMicros).multiply(BigDecimal.valueOf(t))) >= 0)
                .toList();
    }

    public static BudgetStatus status(BudgetPeriod p) {
        long forecast = p.closed() ? p.spentMicros() : p.forecastMicros();
        return new BudgetStatus(p.period(), Micros.toCents(p.amountMicros()), Micros.toCents(p.spentMicros()), Micros.toCents(forecast),
                percent(p.spentMicros(), p.amountMicros()), percent(forecast, p.amountMicros()), state(p.amountMicros(), p.spentMicros(), forecast),
                p.alertsSent(), p.asOf());
    }
}
