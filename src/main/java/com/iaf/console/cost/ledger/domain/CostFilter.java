package com.iaf.console.cost.ledger.domain;

import java.util.Map;

/** Which buckets a report counts: a service, a type, a region and tag values, each optional. */
public record CostFilter(String service, String type, String region, Map<String, String> tags) {

    public CostFilter {
        service = blank(service);
        type = blank(type);
        region = blank(region);
        tags = tags == null ? Map.of() : Map.copyOf(tags);
    }

    public static CostFilter none() {
        return new CostFilter(null, null, null, Map.of());
    }

    public boolean matches(Bucket b) {
        return (service == null || service.equals(b.service())) && (type == null || type.equals(b.type()))
                && (region == null || region.equals(b.region()))
                && tags.entrySet().stream().allMatch(e -> e.getValue().equals(b.tags().get(e.getKey())));
    }

    /** The same filter with one more tag value. */
    public CostFilter withTag(String key, String value) {
        java.util.Map<String, String> t = new java.util.LinkedHashMap<>(tags);
        t.put(key, value);
        return new CostFilter(service, type, region, t);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
