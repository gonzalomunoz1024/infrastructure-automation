package com.iaf.console.state.inventory.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.Problem;

/** The contract's tag rules: names {@code [a-z0-9-]{1,32}}, values up to 256 characters, at most 50 tags. */
public final class TagRules {

    public static final Pattern NAME = Pattern.compile("^[a-z0-9-]{1,32}$");
    public static final int MAX_VALUE = 256;
    public static final int MAX_TAGS = 50;

    private TagRules() {
    }

    /** The problems of a set of tags to apply, empty when they are fine. */
    public static List<Problem> check(Map<String, String> set, List<String> removed, int resultingCount) {
        List<Problem> problems = new ArrayList<>();
        if (set != null) {
            for (Map.Entry<String, String> tag : set.entrySet()) {
                if (tag.getKey() == null || !NAME.matcher(tag.getKey()).matches()) {
                    problems.add(Problem.error("tag.name", "/set/" + tag.getKey(), "a tag name is [a-z0-9-]{1,32}"));
                }
                if (tag.getValue() == null) {
                    problems.add(Problem.error("tag.value", "/set/" + tag.getKey(), "a tag value is required"));
                } else if (tag.getValue().length() > MAX_VALUE) {
                    problems.add(Problem.error("tag.value", "/set/" + tag.getKey(), "a tag value is at most " + MAX_VALUE + " characters"));
                }
            }
        }
        if (removed != null) {
            for (int i = 0; i < removed.size(); i++) {
                String name = removed.get(i);
                if (name == null || !NAME.matcher(name).matches()) {
                    problems.add(Problem.error("tag.name", "/removed/" + i, "a tag name is [a-z0-9-]{1,32}"));
                }
            }
        }
        if (resultingCount > MAX_TAGS) {
            problems.add(Problem.error("tag.count", "/set", "a resource has at most " + MAX_TAGS + " tags"));
        }
        return problems;
    }

    public static void require(Map<String, String> set, List<String> removed, int resultingCount) {
        List<Problem> problems = check(set, removed, resultingCount);
        if (!problems.isEmpty()) {
            throw new InvalidInputException("the tags are not valid", problems);
        }
    }
}
