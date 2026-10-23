package com.iaf.console.drift.drift.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.fasterxml.jackson.annotation.JsonInclude;

/** An amount a month in one currency, and the price list it comes from: the contract's {@code Money}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Money(BigDecimal amount, String currency, String basis) {

    public Money {
        amount = (amount == null ? BigDecimal.ZERO : amount).setScale(2, RoundingMode.HALF_UP);
        currency = currency == null || currency.isBlank() ? "USD" : currency;
    }

    public static Money of(double amount, String currency, String basis) {
        return new Money(BigDecimal.valueOf(amount), currency, basis);
    }

    /** This amount less another of the same currency, never below zero; null when the currencies differ. */
    public Money less(Money other) {
        if (other == null) {
            return this;
        }
        if (!currency.equals(other.currency)) {
            return null;
        }
        return new Money(amount.subtract(other.amount).max(BigDecimal.ZERO), currency, basis);
    }
}
