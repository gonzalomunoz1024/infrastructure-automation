package com.iaf.console.bff.resourcegroups.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import com.iaf.console.kit.errors.Problem;

/**
 * What a group matches: the resources of these types (every type when there are none) that carry
 * every one of these tags with exactly its value, as the resource lists filter {@code tag.<name>}.
 * The tags are kept sorted by name.
 */
public record GroupQuery(List<String> types, Map<String, String> tags) {

    public static final int MAX_TAGS = 20;
    public static final int MAX_TAG_VALUE = 256;
    public static final String TYPE_UNKNOWN = "type.unknown";
    public static final String TAGS_REQUIRED = "group.query.tags-required";
    /** A tag name as the state service takes it. */
    static final Pattern TAG_NAME = Pattern.compile("^[a-z0-9-]{1,32}$");

    public GroupQuery {
        // copies that keep a null element or value, so the check can point at it
        types = types == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(types));
        tags = tags == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(tags));
    }

    /** The same query with each type once, in the order they were first given. */
    GroupQuery distinct() {
        return new GroupQuery(List.copyOf(new LinkedHashSet<>(types)), tags);
    }

    /** Adds the query's problems, each on its JSON pointer; {@code known} are the type names of the region's catalogue. */
    void check(Set<String> known, List<Problem> problems) {
        for (int i = 0; i < types.size(); i++) {
            String type = types.get(i);
            if (type == null || !known.contains(type)) {
                problems.add(Problem.error(TYPE_UNKNOWN, "/query/types/" + i, "no type '" + type + "' in this region"));
            }
        }
        if (tags.isEmpty()) {
            problems.add(Problem.error(TAGS_REQUIRED, "/query/tags", "a group's query names at least one tag"));
        } else if (tags.size() > MAX_TAGS) {
            problems.add(Problem.error("length", "/query/tags", "a group's query names at most " + MAX_TAGS + " tags"));
        }
        tags.forEach((name, value) -> {
            String field = "/query/tags/" + pointer(name);
            if (!TAG_NAME.matcher(name).matches()) {
                problems.add(Problem.error("tag.name", field, "a tag name is 1 to 32 lowercase letters, digits or '-'"));
            } else if (value == null || value.length() > MAX_TAG_VALUE) {
                problems.add(Problem.error("tag.value", field, "a tag value is a string of at most " + MAX_TAG_VALUE + " characters"));
            }
        });
    }

    /** One reference token of a JSON pointer, escaped. */
    static String pointer(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }
}
