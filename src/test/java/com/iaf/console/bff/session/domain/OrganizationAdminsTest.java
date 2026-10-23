package com.iaf.console.bff.session.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class OrganizationAdminsTest {

    @Test
    void nobodyIsAnAdminWhenNoGroupIsConfigured() {
        assertFalse(OrganizationAdmins.none().includes(List.of("console-org-admins")));
        assertFalse(new OrganizationAdmins(Arrays.asList(" ", null)).includes(List.of(" ")));
        assertFalse(new OrganizationAdmins(null).includes(List.of("admin")));
    }

    @Test
    void aPersonInOneOfTheGroupsIsOneExactlyAsTheProviderNamesIt() {
        OrganizationAdmins admins = new OrganizationAdmins(List.of("console-org-admins", "platform-leads "));
        assertTrue(admins.includes(List.of("team-compute", "console-org-admins")));
        assertTrue(admins.includes(List.of("platform-leads")));
        assertFalse(admins.includes(List.of("Console-Org-Admins")), "group names are compared as the provider sends them");
        assertFalse(admins.includes(List.of("team-compute")));
        assertFalse(admins.includes(null));
    }
}
