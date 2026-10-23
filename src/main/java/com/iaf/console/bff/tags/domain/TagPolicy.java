package com.iaf.console.bff.tags.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.iaf.console.kit.errors.Problem;

/**
 * The organization's required tags as they stand for one account (WP 4.4): every create through
 * the console names each of them itself, with a value the tag may take. The defaults are what the
 * create form pre-fills (the account's owning team as the owner, its cost centre, the
 * organization's data class); the BFF never adds a tag the request left out, so an API or MCP
 * caller is refused like the form until it names them.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TagPolicy(String accountId, List<RequiredTag> required) {

    /** A required tag missing or blank, or about to be removed. */
    public static final String REQUIRED = "tags.required";
    /** A required tag with a value it may not take. */
    public static final String INVALID = "tags.invalid";

    public TagPolicy {
        required = required == null ? List.of() : List.copyOf(required);
    }

    /** What the tags lack or get wrong, as problems on {@code <prefix>/<key>}. */
    public List<Problem> check(Map<String, String> tags, String prefix) {
        Map<String, String> given = tags == null ? Map.of() : tags;
        List<Problem> problems = new ArrayList<>();
        for (RequiredTag tag : required) {
            String value = given.get(tag.key());
            if (value == null || value.isBlank()) {
                problems.add(Problem.error(REQUIRED, prefix + "/" + tag.key(), tag.title() + " is required"
                        + (tag.defaultValue() == null ? "" : " (this account uses " + tag.defaultValue() + ")")));
            } else if (!tag.accepts(value)) {
                problems.add(Problem.error(INVALID, prefix + "/" + tag.key(), tag.allowedSentence()));
            }
        }
        return problems;
    }

    /**
     * What a change to a resource's tags may not do: remove a required tag it carries, or give one
     * a value the tag may not take. A resource that never carried a required tag (one imported or
     * created before the policy) is not made to take it by an unrelated change.
     */
    public List<Problem> checkChange(Map<String, String> before, Map<String, String> set, Collection<String> removed, String prefix) {
        Map<String, String> had = before == null ? Map.of() : before;
        Map<String, String> setting = set == null ? Map.of() : set;
        List<Problem> problems = new ArrayList<>();
        for (RequiredTag tag : required) {
            String value = setting.get(tag.key());
            if (value != null && !tag.accepts(value)) {
                problems.add(Problem.error(INVALID, prefix + "/" + tag.key(), tag.allowedSentence()));
            } else if (value == null && removed != null && removed.contains(tag.key()) && had.containsKey(tag.key())) {
                problems.add(Problem.error(REQUIRED, prefix + "/" + tag.key(), tag.title() + " is required and stays on the resource"));
            }
        }
        return problems;
    }

    /** The defaults the form pre-fills, by key. */
    public Map<String, String> defaults() {
        Map<String, String> out = new LinkedHashMap<>();
        for (RequiredTag tag : required) {
            if (tag.defaultValue() != null) {
                out.put(tag.key(), tag.defaultValue());
            }
        }
        return out;
    }

    /** The keys a problem list names, in the order of the policy. */
    public static List<String> keysOf(List<Problem> problems) {
        List<String> keys = new ArrayList<>();
        for (Problem p : problems) {
            String pointer = p.field() == null ? "" : p.field();
            String key = pointer.substring(pointer.lastIndexOf('/') + 1);
            if (!key.isEmpty() && !keys.contains(key)) {
                keys.add(key);
            }
        }
        return keys;
    }
}
