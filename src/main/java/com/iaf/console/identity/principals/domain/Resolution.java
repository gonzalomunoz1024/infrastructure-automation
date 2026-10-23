package com.iaf.console.identity.principals.domain;

import java.util.List;

/** The answer of {@code POST /v1/principals:resolve}. */
public record Resolution(User user, List<AccountMembership> accounts) {
}
