package com.iaf.platform.runs.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.workflows.domain.WorkflowYaml;

/**
 * One {@code kind: PriceList} file: what the things a run creates cost per month, by provider
 * action, either flat or by the value of one field of the action's input. A preview prices each
 * create call with it when the action's plan answers no price itself. The platform only matches
 * provider and action names and reads one input field; what is being priced is the content's
 * business.
 */
public record PriceList(String name, String owner, String currency, List<Price> prices) {

    public static final String KIND = "PriceList";
    public static final String DEFAULT_CURRENCY = "USD";

    /**
     * One entry: {@code monthly} is the flat price, or, with {@code by}, {@code byValue} holds a price
     * per value of that input field (a dotted path for a nested field).
     */
    public record Price(String provider, String action, Double monthly, String by, Map<String, Double> byValue) {
    }

    /** The price of one call, and the list that gave it. */
    public record Quote(double monthly, String currency, String list) {
    }

    public static PriceList parse(String yaml) {
        return fromJson(WorkflowYaml.readTree(yaml));
    }

    public static PriceList fromJson(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("A price list file is a YAML mapping");
        }
        String kind = root.path("kind").asText(null);
        if (kind != null && !KIND.equals(kind)) {
            throw new IllegalArgumentException("kind must be PriceList, was " + kind);
        }
        JsonNode md = root.path("metadata");
        String name = md.path("name").asText(null);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("metadata.name is required");
        }
        JsonNode spec = root.path("spec");
        String currency = spec.path("currency").asText(DEFAULT_CURRENCY);
        JsonNode entries = spec.path("prices");
        if (!entries.isArray() || entries.isEmpty()) {
            throw new IllegalArgumentException("spec.prices is a list of { provider, action, monthly }");
        }
        List<Price> prices = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            prices.add(price(entries.get(i), "spec.prices[" + i + "]"));
        }
        return new PriceList(name, md.path("owner").asText(null), currency, List.copyOf(prices));
    }

    private static Price price(JsonNode e, String at) {
        String provider = e.path("provider").asText(null);
        String action = e.path("action").asText(null);
        if (provider == null || provider.isBlank() || action == null || action.isBlank()) {
            throw new IllegalArgumentException(at + " needs a provider and an action");
        }
        JsonNode monthly = e.get("monthly");
        String by = e.path("by").asText(null);
        if (monthly != null && monthly.isNumber()) {
            return new Price(provider, action, monthly.asDouble(), null, Map.of());
        }
        if (monthly != null && monthly.isObject()) {
            if (by == null || by.isBlank()) {
                throw new IllegalArgumentException(at + ": a price per value needs by, the input field it depends on");
            }
            Map<String, Double> byValue = new LinkedHashMap<>();
            monthly.fields().forEachRemaining(v -> {
                if (!v.getValue().isNumber()) {
                    throw new IllegalArgumentException(at + ".monthly." + v.getKey() + " must be a number");
                }
                byValue.put(v.getKey(), v.getValue().asDouble());
            });
            return new Price(provider, action, null, by, Map.copyOf(byValue));
        }
        throw new IllegalArgumentException(at + ".monthly must be a number, or a number per value of the input field named by by");
    }

    /** This list's price for a call of {@code provider action} with {@code input}; empty when it has none. */
    public Optional<Quote> quote(String provider, String action, JsonNode input) {
        for (Price p : prices) {
            if (!p.provider().equals(provider) || !p.action().equals(action)) {
                continue;
            }
            if (p.monthly() != null) {
                return Optional.of(new Quote(p.monthly(), currency, name));
            }
            String v = key(field(input, p.by()));
            if (v != null && p.byValue().containsKey(v)) {
                return Optional.of(new Quote(p.byValue().get(v), currency, name));
            }
        }
        return Optional.empty();
    }

    /** The first list that prices the call. */
    public static Optional<Quote> quote(List<PriceList> lists, String provider, String action, JsonNode input) {
        if (lists == null || provider == null || action == null) {
            return Optional.empty();
        }
        for (PriceList l : lists) {
            Optional<Quote> q = l.quote(provider, action, input);
            if (q.isPresent()) {
                return q;
            }
        }
        return Optional.empty();
    }

    /** The value as a price key: text as it is, a whole number without a fraction (4, not 4.0). */
    private static String key(JsonNode v) {
        if (v == null || v.isNull() || !v.isValueNode()) {
            return null;
        }
        if (v.isNumber() && v.asDouble() == Math.rint(v.asDouble()) && !Double.isInfinite(v.asDouble())) {
            return String.valueOf(v.asLong());
        }
        return v.asText();
    }

    private static JsonNode field(JsonNode input, String path) {
        JsonNode n = input;
        for (String part : path.split("\\.")) {
            if (n == null || !n.isObject()) {
                return null;
            }
            n = n.get(part);
        }
        return n;
    }
}
