package com.iaf.console.identity.iam.application;

import java.util.List;

/** The contract's {@code RoleInput}: a null field stays as it is on a change. Policies are ids or names. */
public record RoleInput(String name, String description, List<String> policies) {
}
