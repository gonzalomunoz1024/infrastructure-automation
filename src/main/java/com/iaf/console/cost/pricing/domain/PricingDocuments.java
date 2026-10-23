package com.iaf.console.cost.pricing.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Reads price lists and bindings from the shapes they are written in: a {@code kind: PriceList}
 * file or the platform's {@code PriceList} (P-22, the same fields at the top or under {@code spec}),
 * and a type's {@code cost} block.
 */
public final class PricingDocuments {

    private PricingDocuments() {
    }

    /** A price list from its file ({@code metadata} and {@code spec}) or the platform's flat answer. */
    public static PriceList priceList(JsonNode doc) {
        JsonNode meta = doc.path("metadata");
        JsonNode spec = doc.has("spec") ? doc.path("spec") : doc;
        String name = text(meta, "name", text(doc, "name", null));
        if (name == null) {
            throw new IllegalArgumentException("a price list without a name");
        }
        String title = text(meta, "title", text(doc, "title", null));
        String currency = text(spec, "currency", "USD");
        String version = doc.hasNonNull("version") ? doc.get("version").asText() : text(doc, "hash", "");
        List<Price> prices = new ArrayList<>();
        for (JsonNode p : spec.path("prices")) {
            String provider = text(p, "provider", null);
            String action = text(p, "action", null);
            JsonNode monthly = p.path("monthly");
            if (monthly.isNumber()) {
                prices.add(Price.flat(provider, action, monthly.decimalValue()));
            } else if (monthly.isObject()) {
                Map<String, BigDecimal> perValue = new LinkedHashMap<>();
                monthly.properties().forEach(e -> perValue.put(Price.normalise(e.getKey()), e.getValue().decimalValue()));
                prices.add(Price.perValue(provider, action, text(p, "by", null), perValue));
            } else {
                throw new IllegalArgumentException("price " + provider + " " + action + " of " + name + " has no monthly amount");
            }
        }
        return new PriceList(name, title, currency, version, prices);
    }

    /** A type's binding from its {@code cost} block; null when the type has none. */
    public static PriceBinding binding(String type, JsonNode cost) {
        if (cost == null || cost.isMissingNode() || cost.isNull() || !cost.isObject()) {
            return null;
        }
        return new PriceBinding(type, text(cost, "priceList", null), text(cost, "provider", null), text(cost, "action", null), text(cost, "by", null));
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? fallback : v.asText();
    }
}
