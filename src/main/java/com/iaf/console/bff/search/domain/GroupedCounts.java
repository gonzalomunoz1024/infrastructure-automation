package com.iaf.console.bff.search.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The search indexer's counts as the console answers them: {@code total}, {@code byState} (every
 * state, in the console's order), {@code drift}, {@code cappedAt} only when a count stopped at a
 * cap, and with a grouping {@code groups}, each {@code { key, total, byState, drift, service? }},
 * the largest total first (then by key), at most {@value #MAX_GROUPS}. Several answers, the
 * accounts asked in chunks, are added up: the totals, states and drift, and each group's numbers
 * by key; when one of them was capped the sum is a lower bound and {@code cappedAt} is the sum.
 */
public final class GroupedCounts {

    public static final int MAX_GROUPS = 100;

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private GroupedCounts() {
    }

    /** No resource counted at all, with an empty list of groups when grouped. */
    public static ObjectNode empty(boolean grouped) {
        return sum(List.of(), grouped);
    }

    /** One answer of the indexer, as the console writes it. */
    public static ObjectNode of(JsonNode answer, boolean grouped) {
        return sum(List.of(answer), grouped);
    }

    /** Several answers of the indexer added up. */
    public static ObjectNode sum(List<JsonNode> answers, boolean grouped) {
        Numbers all = new Numbers();
        Map<String, Numbers> groups = new LinkedHashMap<>();
        Long cappedAt = null;
        for (JsonNode answer : answers) {
            all.add(answer);
            JsonNode cap = answer.get("cappedAt");
            if (cap != null && cap.isNumber()) {
                cappedAt = answers.size() == 1 ? Long.valueOf(cap.asLong()) : Long.valueOf(-1L);
            }
            for (JsonNode group : answer.path("groups")) {
                JsonNode key = group.get("key");
                if (key != null && !key.isNull()) {
                    Numbers numbers = groups.computeIfAbsent(key.asText(), k -> new Numbers());
                    numbers.add(group);
                    if (numbers.service == null && group.hasNonNull("service")) {
                        numbers.service = group.get("service").asText();
                    }
                }
            }
        }
        ObjectNode out = JSON.objectNode();
        all.write(out);
        if (cappedAt != null) {
            out.put("cappedAt", cappedAt < 0 ? all.total : cappedAt);
        }
        if (grouped) {
            List<Map.Entry<String, Numbers>> sorted = new ArrayList<>(groups.entrySet());
            sorted.sort(Comparator.comparing((Map.Entry<String, Numbers> e) -> e.getValue().total).reversed().thenComparing(Map.Entry::getKey));
            ArrayNode list = out.putArray("groups");
            for (Map.Entry<String, Numbers> e : sorted.subList(0, Math.min(MAX_GROUPS, sorted.size()))) {
                ObjectNode group = list.addObject();
                group.put("key", e.getKey());
                e.getValue().write(group);
                if (e.getValue().service != null) {
                    group.put("service", e.getValue().service);
                }
            }
        }
        return out;
    }

    /** A total, the count of every state and the drifted ones, added up. */
    private static final class Numbers {
        long total;
        long drift;
        String service;
        final Map<String, Long> byState = new LinkedHashMap<>();

        Numbers() {
            for (String state : ResourceCounts.STATES) {
                byState.put(state, 0L);
            }
        }

        void add(JsonNode node) {
            total += node.path("total").asLong();
            drift += node.path("drift").asLong();
            node.path("byState").properties().forEach(e -> byState.merge(e.getKey(), e.getValue().asLong(), Long::sum));
        }

        void write(ObjectNode out) {
            out.put("total", total);
            ObjectNode states = out.putObject("byState");
            byState.forEach(states::put);
            out.put("drift", drift);
        }
    }
}
