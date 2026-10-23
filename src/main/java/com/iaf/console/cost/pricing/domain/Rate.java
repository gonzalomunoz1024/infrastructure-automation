package com.iaf.console.cost.pricing.domain;

/**
 * What a resource costs a month at a price list's rate: the amount in micros, the currency, whether
 * a price covered it ({@code unpriced} otherwise, at 0), the rule behind it, and whether the
 * attribute came from the provider's observation or the desired attributes.
 */
public record Rate(long monthlyMicros, String currency, boolean priced, PriceRule rule, String pricedFrom) {

    public static final String OBSERVED = "observed";
    public static final String DESIRED = "desired";

    public static Rate unpriced(String currency, PriceRule rule) {
        return new Rate(0, currency, false, rule, null);
    }

    public String source() {
        return priced ? "list-prices" : "unpriced";
    }
}
