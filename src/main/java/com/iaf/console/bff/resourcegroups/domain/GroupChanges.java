package com.iaf.console.bff.resourcegroups.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.iaf.console.kit.errors.Problem;

/**
 * A change's body: a new description, a new query (which replaces the whole query), or both; what
 * is left out stays. A name, when sent, must be the group's own, as a name never changes.
 */
public record GroupChanges(String name, String description, GroupQuery query) {

    public static final String NAME_IMMUTABLE = "group.name-immutable";

    /** The body of a change that sent none. */
    public static GroupChanges none() {
        return new GroupChanges(null, null, null);
    }

    /** The changes' problems against the group they change; {@code known} are the type names of the region's catalogue. */
    public List<Problem> problems(ResourceGroup current, Set<String> known) {
        List<Problem> problems = new ArrayList<>();
        if (name != null && !name.equals(current.name())) {
            problems.add(Problem.error(NAME_IMMUTABLE, "/name", "a group's name never changes; create a group with the new name instead"));
        }
        ResourceGroup.checkDescription(description, problems);
        if (query != null) {
            query.check(known, problems);
        }
        return problems;
    }
}
