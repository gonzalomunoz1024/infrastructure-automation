package com.iaf.console.bff.session.application;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The organization admins (hub decision 34): the identity provider groups whose members are
 * organization admins, read from the sign-in's groups claim. Empty by default: nobody is.
 */
@ConfigurationProperties(prefix = "console.organization")
public record OrganizationProperties(List<String> adminGroups) {

    public OrganizationProperties {
        adminGroups = adminGroups == null ? List.of() : List.copyOf(adminGroups);
    }
}
