package com.iaf.console.bff.cost.domain;

import java.math.BigDecimal;
import java.util.Objects;

/** An amount of money in a currency (ISO 4217; every price list in force today is USD). */
public record Money(BigDecimal amount, String currency) {

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
    }

    public static Money of(double amount, String currency) {
        return new Money(BigDecimal.valueOf(amount), currency == null || currency.isBlank() ? "USD" : currency);
    }

    public double asDouble() {
        return amount.doubleValue();
    }
}
