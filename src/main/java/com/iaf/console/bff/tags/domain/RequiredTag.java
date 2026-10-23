package com.iaf.console.bff.tags.domain;

import java.util.List;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One tag the organization requires on every resource created through the console: its key and
 * title, the values it may take ({@code values}, or a {@code pattern}), and the value the create
 * form pre-fills ({@code default}) with where it comes from ({@code defaultFrom}: {@code account}
 * for the account's own value, {@code organization} for the organization's, or
 * {@code input.<field>} when the form copies one of the create's own fields).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RequiredTag(String key, String title, String description, List<String> values, String pattern,
        @JsonProperty("default") String defaultValue, String defaultFrom) {

    public static final String FROM_ACCOUNT = "account";
    public static final String FROM_ORGANIZATION = "organization";
    public static final String FROM_INPUT = "input.";

    public RequiredTag {
        values = values == null || values.isEmpty() ? null : List.copyOf(values);
        title = title == null || title.isBlank() ? key : title;
        if (pattern != null && !pattern.isBlank()) {
            Pattern.compile(pattern);
        }
    }

    /** Whether a value is one the tag may take. */
    public boolean accepts(String value) {
        if (value == null || value.isBlank() || value.length() > 256) {
            return false;
        }
        if (values != null) {
            return values.contains(value);
        }
        return pattern == null || pattern.isBlank() || Pattern.matches(pattern, value);
    }

    /** The sentence for a value it may not take. */
    @JsonIgnore
    public String allowedSentence() {
        if (values != null) {
            return title + " is one of " + String.join(", ", values);
        }
        if (pattern != null && !pattern.isBlank()) {
            return title + " must match " + pattern;
        }
        return title + " is at most 256 characters";
    }
}
