package com.iaf.console.identity.principals.application;

import java.util.List;

/** The body of {@code POST /v1/accounts/{accountId}/users}; groups are ids or names. */
public record AddUser(String email, String name, List<String> groups) {
}
