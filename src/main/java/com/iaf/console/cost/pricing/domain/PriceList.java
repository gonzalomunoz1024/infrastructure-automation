package com.iaf.console.cost.pricing.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** A {@code kind: PriceList}: its name, currency, the version it was read at, and its prices. */
public record PriceList(String name, String title, String currency, String version, List<Price> prices) {

    public PriceList {
        Objects.requireNonNull(name, "name");
        currency = currency == null || currency.isBlank() ? "USD" : currency;
        version = version == null ? "" : version;
        prices = prices == null ? List.of() : List.copyOf(prices);
    }

    public Optional<Price> price(String provider, String action) {
        return prices.stream().filter(p -> p.provider().equals(provider) && p.action().equals(action)).findFirst();
    }
}
