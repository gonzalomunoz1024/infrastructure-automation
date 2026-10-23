package com.iaf.console.bff.cost.domain;

/** What the cost service's list prices say a create would cost a month, with the line a preview shows; {@code priced} false when no price covers it. */
public record Estimate(Money monthly, String detail, boolean priced) {
}
