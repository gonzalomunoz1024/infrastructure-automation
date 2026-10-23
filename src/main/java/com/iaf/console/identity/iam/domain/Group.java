package com.iaf.console.identity.iam.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A group of an account: the roles its members hold, and the identity provider groups whose
 * members join it at sign-in. Who is in it lives on the memberships, not here.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Group(String groupId, @JsonIgnore String accountId, String name, String description, List<String> roles, List<String> idpGroups, boolean builtIn,
        long version, Instant createdAt, Instant updatedAt) {

    public Group {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(name, "name");
        roles = roles == null ? List.of() : List.copyOf(roles);
        idpGroups = idpGroups == null ? List.of() : List.copyOf(idpGroups);
    }

    public static Group create(String groupId, String accountId, String name, String description, List<String> roles, List<String> idpGroups, boolean builtIn,
            Instant now) {
        return new Group(groupId, accountId, name, description, roles, idpGroups, builtIn, 1, now, now);
    }

    public Group changed(String newName, String newDescription, List<String> newRoles, List<String> newIdpGroups, Instant now) {
        return new Group(groupId, accountId, newName == null ? name : newName, newDescription == null ? description : newDescription,
                newRoles == null ? roles : newRoles, newIdpGroups == null ? idpGroups : newIdpGroups, builtIn, version + 1, createdAt, now);
    }
}
