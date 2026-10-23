package com.iaf.console.bff.assistant.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A list's own filter, in the contract's {@code SearchFilter} names: {@code q} for what the other
 * names cannot say, the console's {@code state}, {@code drift}, {@code region}, and tags (an
 * empty value means "has the tag"). Whatever an assistant writes is reduced to these fields and to
 * values the list takes before the console sees it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SearchFilter(String q, String state, Boolean drift, String region, Map<String, String> tags) {

    /** The console's resource states, as the list's {@code state} takes them. */
    public static final List<String> STATES = List.of("pending", "active", "updating", "deleting", "deleted", "failed");
    public static final int MAX_Q = 256;
    public static final int MAX_TAGS = 20;
    public static final int MAX_TAG_VALUE = 256;
    private static final Pattern TAG_NAME = Pattern.compile("^[a-z0-9-]{1,32}$");
    private static final Pattern REGION = Pattern.compile("^[a-z0-9-]{2,32}$");

    public SearchFilter {
        tags = tags == null || tags.isEmpty() ? null : Collections.unmodifiableMap(new LinkedHashMap<>(tags));
    }

    /** Nothing to filter on. */
    @JsonIgnore
    public boolean isEmpty() {
        return q == null && state == null && drift == null && region == null && (tags == null || tags.isEmpty());
    }

    /**
     * The filter with only what the list takes: {@code q} of at most 256 characters, one of the
     * console's states, a region name, and at most 20 tags of valid names with values of at most
     * 256 characters. What is dropped is said in {@code notes}; null when nothing is left.
     */
    public SearchFilter reduced(List<String> notes) {
        String keptQ = null;
        if (q != null && !q.isBlank()) {
            String stripped = PlainText.line(q, Integer.MAX_VALUE);
            if (stripped != null && stripped.length() <= MAX_Q) {
                keptQ = stripped;
            } else {
                notes.add("q is longer than " + MAX_Q + " characters");
            }
        }
        String keptState = null;
        if (state != null) {
            String lower = state.strip().toLowerCase(Locale.ROOT);
            if (STATES.contains(lower)) {
                keptState = lower;
            } else {
                notes.add("state '" + state + "' is not a console state");
            }
        }
        String keptRegion = null;
        if (region != null) {
            if (REGION.matcher(region).matches()) {
                keptRegion = region;
            } else {
                notes.add("region '" + region + "' is not a region name");
            }
        }
        Map<String, String> keptTags = new LinkedHashMap<>();
        if (tags != null) {
            for (Map.Entry<String, String> tag : tags.entrySet()) {
                String name = tag.getKey() == null ? "" : tag.getKey().strip();
                String value = tag.getValue() == null ? "" : tag.getValue().strip();
                if (!TAG_NAME.matcher(name).matches()) {
                    notes.add("tag '" + tag.getKey() + "' is not a tag name");
                } else if (value.length() > MAX_TAG_VALUE) {
                    notes.add("the value of tag '" + name + "' is longer than " + MAX_TAG_VALUE + " characters");
                } else if (keptTags.size() >= MAX_TAGS) {
                    notes.add("more than " + MAX_TAGS + " tags");
                    break;
                } else {
                    keptTags.put(name, value);
                }
            }
        }
        SearchFilter kept = new SearchFilter(keptQ, keptState, drift, keptRegion, keptTags);
        return kept.isEmpty() ? null : kept;
    }

    /** A filter as an assistant wrote it, read leniently: fields of another type are left out; null when it is no object. */
    public static SearchFilter read(JsonNode node, List<String> notes) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (!node.isObject()) {
            notes.add("the filter is not an object");
            return null;
        }
        Boolean drift = null;
        JsonNode d = node.get("drift");
        if (d != null && d.isBoolean()) {
            drift = d.booleanValue();
        } else if (d != null && d.isTextual() && ("true".equalsIgnoreCase(d.textValue()) || "false".equalsIgnoreCase(d.textValue()))) {
            drift = Boolean.parseBoolean(d.textValue());
        } else if (d != null && !d.isNull()) {
            notes.add("drift is not true or false");
        }
        Map<String, String> tags = new LinkedHashMap<>();
        JsonNode t = node.get("tags");
        if (t != null && t.isObject()) {
            for (Map.Entry<String, JsonNode> tag : t.properties()) {
                if (tag.getValue().isValueNode() && !tag.getValue().isNull()) {
                    tags.put(tag.getKey(), tag.getValue().asText());
                } else if (tag.getValue().isNull()) {
                    tags.put(tag.getKey(), "");
                } else {
                    notes.add("the value of tag '" + tag.getKey() + "' is not text");
                }
            }
        } else if (t != null && !t.isNull()) {
            notes.add("tags is not an object");
        }
        return new SearchFilter(text(node, "q"), text(node, "state"), drift, text(node, "region"), tags);
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() && !value.textValue().isBlank() ? value.textValue() : null;
    }
}
