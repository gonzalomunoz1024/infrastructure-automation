package com.iaf.console.identity.principals.application;

import java.util.List;

import com.iaf.console.identity.principals.domain.UserStatus;

/** The body of {@code PATCH /v1/accounts/{accountId}/users/{userId}}; a null field stays as it is. */
public record UpdateUser(String name, UserStatus status, List<String> groups) {
}
