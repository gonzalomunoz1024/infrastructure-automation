package com.iaf.console.cost.pricing.domain;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A type's {@code cost} block (P-22): resources of {@code type} are priced by the price of
 * {@code provider} {@code action} in {@code priceList}, by the value of their attribute {@code by}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PriceBinding(String type, String priceList, String provider, String action, String by) {

    public PriceBinding {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(priceList, "priceList");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(action, "action");
        by = by == null || by.isBlank() ? null : by;
    }
}
