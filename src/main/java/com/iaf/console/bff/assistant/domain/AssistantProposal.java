package com.iaf.console.bff.assistant.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Something the console opens for the person to review, the contract's {@code AssistantProposal}:
 * a filtered list ({@code filter}), a pre-filled create wizard ({@code create}), a pre-filled
 * action on one resource ({@code action}) or a console page ({@code open}). The copilot never runs
 * it; every change still goes through its preview and IAM.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AssistantProposal(
        String kind,
        String label,
        String service,
        String type,
        SearchFilter filter,
        String sort,
        Map<String, Object> input,
        List<String> missing,
        String wfrn,
        String verb,
        String path) {

    public static final String FILTER = "filter";
    public static final String CREATE = "create";
    public static final String ACTION = "action";
    public static final String OPEN = "open";
    public static final List<String> KINDS = List.of(FILTER, CREATE, ACTION, OPEN);
    /** The most proposals one answer carries. */
    public static final int MAX = 5;
    /** The list's sorts. */
    public static final List<String> SORTS = List.of("name", "-name", "created", "-created", "updated", "-updated");

    public AssistantProposal {
        if (input != null) {
            Map<String, Object> copy = new LinkedHashMap<>();
            input.forEach((k, v) -> {
                if (k != null && v != null) {
                    copy.put(k, v);
                }
            });
            input = Collections.unmodifiableMap(copy);
        }
        missing = missing == null ? null : List.copyOf(missing);
    }

    public static AssistantProposal filter(String label, String service, String type, SearchFilter filter, String sort) {
        return new AssistantProposal(FILTER, label, service, type, filter, sort, null, null, null, null, null);
    }

    public static AssistantProposal create(String label, String service, String type, Map<String, Object> input, List<String> missing) {
        return new AssistantProposal(CREATE, label, service, type, null, null, input == null ? Map.of() : input, missing == null ? List.of() : missing, null,
                null, null);
    }

    public static AssistantProposal action(String label, String service, String type, String wfrn, String verb, Map<String, Object> input,
            List<String> missing) {
        return new AssistantProposal(ACTION, label, service, type, null, null, input == null ? Map.of() : input, missing == null ? List.of() : missing, wfrn,
                verb, null);
    }

    public static AssistantProposal open(String label, String path) {
        return new AssistantProposal(OPEN, label, null, null, null, null, null, null, null, null, path);
    }

    /**
     * A proposal as an assistant wrote it, read leniently: a field of another type is left out
     * (and said in {@code notes}), so the review decides on what is there; null when it is no object.
     */
    public static AssistantProposal read(JsonNode node, List<String> notes) {
        if (node == null || !node.isObject()) {
            notes.add("a proposal is not an object");
            return null;
        }
        Map<String, Object> input = null;
        JsonNode in = node.get("input");
        if (in != null && in.isObject()) {
            input = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> field : in.properties()) {
                Object value = value(field.getValue());
                if (value != null) {
                    input.put(field.getKey(), value);
                }
            }
        } else if (in != null && !in.isNull()) {
            notes.add("input is not an object");
        }
        List<String> missing = null;
        JsonNode m = node.get("missing");
        if (m != null && m.isArray()) {
            missing = new ArrayList<>();
            for (JsonNode name : m) {
                if (name.isTextual()) {
                    missing.add(name.textValue());
                }
            }
        }
        return new AssistantProposal(SearchFilter.text(node, "kind"), SearchFilter.text(node, "label"), SearchFilter.text(node, "service"),
                SearchFilter.text(node, "type"), SearchFilter.read(node.get("filter"), notes), SearchFilter.text(node, "sort"), input, missing,
                SearchFilter.text(node, "wfrn"), SearchFilter.text(node, "verb"), SearchFilter.text(node, "path"));
    }

    /** A JSON value as a plain Java one (text, number, boolean, list, map); null for null. */
    static Object value(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isIntegralNumber()) {
            // as Jackson reads a number back into a map, so a stored proposal reads back equal
            return node.canConvertToInt() ? (Object) node.intValue() : node.canConvertToLong() ? (Object) node.longValue() : (Object) node.doubleValue();
        }
        if (node.isNumber()) {
            return node.doubleValue();
        }
        if (node.isArray()) {
            List<Object> items = new ArrayList<>();
            for (JsonNode item : node) {
                Object value = value(item);
                if (value != null) {
                    items.add(value);
                }
            }
            return items;
        }
        if (node.isObject()) {
            Map<String, Object> fields = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                Object value = value(field.getValue());
                if (value != null) {
                    fields.put(field.getKey(), value);
                }
            }
            return fields;
        }
        return node.asText();
    }
}
