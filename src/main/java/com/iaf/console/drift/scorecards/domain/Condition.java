package com.iaf.console.drift.scorecards.domain;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A rule's declarative condition (contracts/rule-packs/reference.mjs on the hub is its reference):
 * {@code all}, {@code any} or {@code not} of conditions, or one test of the value at a dotted path
 * of the evaluation input. Every test but {@code present: false} is false where the value is
 * missing (absent or null), so a rule never passes on data it does not have; numbers compare by
 * value and {@code matches} is a whole-string match.
 */
public sealed interface Condition {

    boolean holds(JsonNode input);

    /** Every condition holds. */
    record All(List<Condition> conditions) implements Condition {
        @Override
        public boolean holds(JsonNode input) {
            return conditions.stream().allMatch(c -> c.holds(input));
        }
    }

    /** At least one condition holds. */
    record Any(List<Condition> conditions) implements Condition {
        @Override
        public boolean holds(JsonNode input) {
            return conditions.stream().anyMatch(c -> c.holds(input));
        }
    }

    record Not(Condition condition) implements Condition {
        @Override
        public boolean holds(JsonNode input) {
            return !condition.holds(input);
        }
    }

    /** One test of one value: {@code op} is present, equals, notEquals, in, notIn, gte, gt, lte, lt or matches. */
    record Test(String path, String op, JsonNode argument, Pattern pattern) implements Condition {
        @Override
        public boolean holds(JsonNode input) {
            JsonNode value = valueAt(input, path);
            boolean missing = value == null;
            return switch (op) {
                case "present" -> {
                    boolean there = !missing && !(value.isTextual() && value.asText().isEmpty());
                    yield argument.asBoolean() == there;
                }
                case "equals" -> !missing && same(value, argument);
                case "notEquals" -> !missing && !same(value, argument);
                case "in" -> !missing && contains(argument, value);
                case "notIn" -> !missing && !contains(argument, value);
                case "gte" -> !missing && value.isNumber() && compare(value, argument) >= 0;
                case "gt" -> !missing && value.isNumber() && compare(value, argument) > 0;
                case "lte" -> !missing && value.isNumber() && compare(value, argument) <= 0;
                case "lt" -> !missing && value.isNumber() && compare(value, argument) < 0;
                case "matches" -> !missing && value.isTextual() && pattern.matcher(value.asText()).matches();
                default -> throw new IllegalStateException("unknown test " + op);
            };
        }
    }

    /** The tests a leaf may name. */
    List<String> OPERATORS = List.of("present", "equals", "notEquals", "in", "notIn", "gte", "gt", "lte", "lt", "matches");

    /** Parses a condition as the rule-pack schema shapes it; a shape it does not know is an IllegalArgumentException. */
    static Condition parse(JsonNode node) {
        if (node == null || !node.isObject() || node.isEmpty()) {
            throw new IllegalArgumentException("a condition is an object");
        }
        if (node.has("all") || node.has("any")) {
            String kind = node.has("all") ? "all" : "any";
            JsonNode items = node.get(kind);
            if (node.size() != 1 || !items.isArray() || items.isEmpty()) {
                throw new IllegalArgumentException(kind + " takes a non-empty list of conditions and nothing beside it");
            }
            List<Condition> parsed = new ArrayList<>();
            items.forEach(c -> parsed.add(parse(c)));
            return "all".equals(kind) ? new All(List.copyOf(parsed)) : new Any(List.copyOf(parsed));
        }
        if (node.has("not")) {
            if (node.size() != 1) {
                throw new IllegalArgumentException("not takes one condition and nothing beside it");
            }
            return new Not(parse(node.get("not")));
        }
        JsonNode path = node.get("path");
        if (path == null || !path.isTextual() || !path.asText().startsWith("resource.") || node.size() != 2) {
            throw new IllegalArgumentException("a test is { path: resource.<...>, <test>: <value> }: " + node);
        }
        String op = null;
        for (Iterator<Map.Entry<String, JsonNode>> it = node.properties().iterator(); it.hasNext();) {
            String name = it.next().getKey();
            if (!"path".equals(name)) {
                op = name;
            }
        }
        if (!OPERATORS.contains(op)) {
            throw new IllegalArgumentException("unknown test " + op + " (one of " + OPERATORS + ")");
        }
        JsonNode arg = node.get(op);
        switch (op) {
            case "present" -> require(arg.isBoolean(), "present takes true or false");
            case "in", "notIn" -> require(arg.isArray() && !arg.isEmpty(), op + " takes a non-empty list");
            case "gte", "gt", "lte", "lt" -> require(arg.isNumber(), op + " takes a number");
            case "matches" -> require(arg.isTextual(), "matches takes a regular expression");
            default -> {
            }
        }
        Pattern pattern = "matches".equals(op) ? Pattern.compile(arg.asText()) : null;
        return new Test(path.asText(), op, arg, pattern);
    }

    /** The value at a dotted path ({@code resource.tags.data-class}); null where absent or null. */
    static JsonNode valueAt(JsonNode input, String path) {
        JsonNode node = input;
        for (String segment : path.split("\\.")) {
            if (node == null || !node.isObject()) {
                return null;
            }
            node = node.get(segment);
        }
        return node == null || node.isNull() || node.isMissingNode() ? null : node;
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalArgumentException(message);
        }
    }

    private static boolean same(JsonNode a, JsonNode b) {
        if (a.isNumber() && b.isNumber()) {
            return compare(a, b) == 0;
        }
        return a.equals(b);
    }

    private static boolean contains(JsonNode list, JsonNode value) {
        for (JsonNode item : list) {
            if (same(value, item)) {
                return true;
            }
        }
        return false;
    }

    private static int compare(JsonNode a, JsonNode b) {
        return a.decimalValue().compareTo(b.decimalValue());
    }
}
