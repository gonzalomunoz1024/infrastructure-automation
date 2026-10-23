package com.iaf.console.bff.digests.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * What the weekly digest remembers of an identity subject between its events, to tell what a
 * change granted: a group's name, members and roles, a user's groups, whether a policy allows
 * every action, and the subject's version after the event.
 */
public record SubjectState(long version, String name, List<String> members, List<String> roles, List<String> groups, boolean allowsEverything) {

    public SubjectState {
        members = members == null ? List.of() : List.copyOf(members);
        roles = roles == null ? List.of() : List.copyOf(roles);
        groups = groups == null ? List.of() : List.copyOf(groups);
    }

    /** The state an event's snapshot shows; null when there is no snapshot (a subject deleted). */
    public static SubjectState of(long version, JsonNode snapshot) {
        if (snapshot == null || !snapshot.isObject()) {
            return null;
        }
        return new SubjectState(version, text(snapshot, "name"), strings(snapshot.path("members")), strings(snapshot.path("roles")),
                strings(snapshot.path("groups")), allowsEverything(snapshot.path("statements")));
    }

    /** Whether a policy's statements allow every action: an allow statement with {@code *} or {@code *:*} among its actions. */
    static boolean allowsEverything(JsonNode statements) {
        if (statements == null || !statements.isArray()) {
            return false;
        }
        for (JsonNode statement : statements) {
            if (!"allow".equals(statement.path("effect").asText("").toLowerCase(Locale.ROOT))) {
                continue;
            }
            for (JsonNode action : statement.path("actions")) {
                String a = action.asText("");
                if (a.equals("*") || a.equals("*:*")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        return value == null || value.isBlank() ? null : value;
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(n -> {
                if (n.isTextual() && !n.asText().isBlank()) {
                    out.add(n.asText());
                }
            });
        }
        return out;
    }
}
