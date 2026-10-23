package com.iaf.console.drift.drift.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * One field a finding is about: a JSON pointer into the resource's attributes, what the console
 * wants, what the provider reports, and whether a decision silenced it (shown, not counted). The
 * empty pointer stands for the whole resource, when the provider no longer has it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FindingField(String path, JsonNode expected, JsonNode observed, Boolean ignored) {

    /** The pointer of a missing resource. */
    public static final String WHOLE = "";

    public FindingField {
        path = path == null ? WHOLE : path;
        ignored = Boolean.TRUE.equals(ignored) ? Boolean.TRUE : null;
    }

    public static FindingField of(String name, JsonNode expected, JsonNode observed) {
        return new FindingField("/" + name, expected, observed, null);
    }

    public FindingField silenced() {
        return new FindingField(path, expected, observed, Boolean.TRUE);
    }

    public boolean counts() {
        return ignored == null;
    }

    /** The attribute name a pointer of one segment names; null for the whole resource or a deeper pointer. */
    public String fieldName() {
        if (path.length() < 2 || path.indexOf('/', 1) >= 0) {
            return null;
        }
        return path.substring(1);
    }

    public boolean sameAs(FindingField other) {
        return path.equals(other.path) && DriftRules.same(expected, other.expected) && DriftRules.same(observed, other.observed)
                && counts() == other.counts();
    }
}
