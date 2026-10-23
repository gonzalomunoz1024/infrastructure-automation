package com.iaf.console.identity.iam.application;

import java.util.List;

/**
 * The contract's {@code RoleInput}: a null field stays as it is on a change. Policies and the
 * boundary are ids or names; an empty boundary removes the role's boundary.
 */
public record RoleInput(String name, String description, List<String> policies, String boundary) {

    public RoleInput(String name, String description, List<String> policies) {
        this(name, description, policies, null);
    }
}
