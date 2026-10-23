package com.iaf.console.fake.content.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.fake.common.Json;

/** A kind PriceList: what previews charge per month for what a run creates, and what P-22 serves to the cost service. */
public final class PriceList {

    private final String name;
    private final JsonNode metadata;
    private final String currency;
    private final List<JsonNode> prices = new ArrayList<>();
    private final String hash;

    public PriceList(JsonNode document) {
        JsonNode file = document == null ? MissingNode.getInstance() : document;
        this.metadata = file.path("metadata");
        this.name = metadata.path("name").asText("list-prices");
        JsonNode spec = file.path("spec");
        this.currency = spec.path("currency").asText("USD");
        for (JsonNode price : spec.path("prices")) {
            prices.add(price);
        }
        this.hash = document == null ? "" : Json.hash(document);
    }

    public static PriceList empty() {
        return new PriceList(null);
    }

    public String name() {
        return name;
    }

    public String currency() {
        return currency;
    }

    public String hash() {
        return hash;
    }

    /** The PriceList document as GET /api/v1/price-lists/{name} answers it (P-22). */
    public ObjectNode document() {
        ObjectNode out = Json.object();
        out.put("name", name);
        for (String field : new String[] {"title", "owner", "description"}) {
            if (metadata.path(field).isTextual()) {
                out.put(field, metadata.path(field).asText());
            }
        }
        out.put("currency", currency);
        out.put("version", 1);
        out.put("hash", hash);
        var items = out.putArray("prices");
        prices.forEach(p -> items.add(p.deepCopy()));
        return out;
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
