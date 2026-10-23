package com.iaf.console.bff.session.domain;

import java.util.List;

/**
 * Who is an organization admin: a person whose identity provider groups (the sign-in's groups
 * claim) include one of {@code groups}, exactly as the provider names them. None configured means
 * nobody is. Read at sign-in and at a step-up, never from a request.
 */
public record OrganizationAdmins(List<String> groups) {

    public OrganizationAdmins {
        groups = groups == null ? List.of() : groups.stream().filter(g -> g != null && !g.isBlank()).map(String::trim).toList();
    }

    public static OrganizationAdmins none() {
        return new OrganizationAdmins(List.of());
    }

    public boolean includes(List<String> idpGroups) {
        return idpGroups != null && !groups.isEmpty() && idpGroups.stream().anyMatch(groups::contains);
    }
}
