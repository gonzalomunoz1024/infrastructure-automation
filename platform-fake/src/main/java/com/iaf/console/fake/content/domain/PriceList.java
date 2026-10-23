package com.iaf.console.fake.content.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

/** What previews charge per month for what a run creates, from the list-prices file. */
public final class PriceList {

    private final String currency;
    private final List<JsonNode> prices = new ArrayList<>();

    public PriceList(JsonNode document) {
        JsonNode spec = document == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : document.path("spec");
        this.currency = spec.path("currency").asText("USD");
        for (JsonNode price : spec.path("prices")) {
            prices.add(price);
        }
    }

    public static PriceList empty() {
        return new PriceList(null);
    }

    public String currency() {
        return currency;
    }

    /** The monthly price of an action for an input, when the list has one. */
    public Optional<Double> monthly(String provider, String action, JsonNode input) {
        for (JsonNode price : prices) {
            if (!provider.equals(price.path("provider").asText()) || !action.equals(price.path("action").asText())) {
                continue;
            }
            JsonNode monthly = price.path("monthly");
            String by = price.path("by").asText(null);
            if (by == null) {
                return monthly.isNumber() ? Optional.of(monthly.asDouble()) : Optional.empty();
            }
            JsonNode value = input == null ? null : input.get(by);
            if (value == null || value.isNull()) {
                return Optional.empty();
            }
            JsonNode priced = monthly.get(value.asText());
            return priced != null && priced.isNumber() ? Optional.of(priced.asDouble()) : Optional.empty();
        }
        return Optional.empty();
    }
}
