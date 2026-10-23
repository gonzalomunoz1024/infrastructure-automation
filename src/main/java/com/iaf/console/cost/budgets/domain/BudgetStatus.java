package com.iaf.console.cost.budgets.domain;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** A budget's month as the API shows it ({@code BudgetStatus} of the contract), amounts rounded to cents. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BudgetStatus(String period, double amount, double spent, double forecast, double percent, Double forecastPercent, String state,
        List<Integer> alertsSent, Instant asOf) {

    public static final String OK = "ok";
    public static final String FORECAST_OVER = "forecast-over";
    public static final String OVER = "over";
}
