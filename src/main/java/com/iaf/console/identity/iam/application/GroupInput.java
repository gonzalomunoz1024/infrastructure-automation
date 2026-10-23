package com.iaf.console.identity.iam.application;

import java.util.List;

/** The contract's {@code GroupInput}: a null field stays as it is on a change. Roles and members are ids or names. */
public record GroupInput(String name, String description, List<String> roles, List<String> members, List<String> idpGroups) {
}
