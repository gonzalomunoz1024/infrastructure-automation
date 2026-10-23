package com.iaf.console.identity.iam.domain;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** A group as the contract serves it: the record plus its members. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GroupView(String groupId, String name, String description, List<String> roles, List<String> members, List<String> idpGroups, boolean builtIn,
        long version, Instant createdAt, Instant updatedAt) {

    public static GroupView of(Group g, List<String> members) {
        return new GroupView(g.groupId(), g.name(), g.description(), g.roles(), members == null ? List.of() : List.copyOf(members), g.idpGroups(), g.builtIn(),
                g.version(), g.createdAt(), g.updatedAt());
    }
}
