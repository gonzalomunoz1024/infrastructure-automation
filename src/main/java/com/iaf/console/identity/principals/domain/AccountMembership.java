package com.iaf.console.identity.principals.domain;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** One account a resolved user may use: its name, the user's groups and roles there (by name), and the default region. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AccountMembership(String accountId, String name, List<String> groups, List<String> roles, String defaultRegion) {
}
