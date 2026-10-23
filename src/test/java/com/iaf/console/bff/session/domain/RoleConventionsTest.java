package com.iaf.console.bff.session.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class RoleConventionsTest {

    @Test
    void rolesFollowTheGroupNames() {
        assertTrue(RoleConventions.fromGroups(List.of("team-compute", "admin")).contains(RoleConventions.ADMIN));
        assertTrue(RoleConventions.fromGroups(List.of("account-owners")).contains(RoleConventions.ADMIN));
        assertTrue(RoleConventions.fromGroups(List.of("ops")).contains(RoleConventions.OPERATOR));
        assertTrue(RoleConventions.fromGroups(List.of("developer")).contains(RoleConventions.OPERATOR));
        assertTrue(RoleConventions.fromGroups(List.of("dba")).contains(RoleConventions.OPERATOR));
        assertTrue(RoleConventions.fromGroups(List.of("audit-team")).contains(RoleConventions.AUDITOR));
        assertEquals(List.of(RoleConventions.VIEWER), RoleConventions.fromGroups(List.of("team-network")));
        assertEquals(List.of(RoleConventions.VIEWER), RoleConventions.fromGroups(List.of()));
    }

    @Test
    void theStrongestRoleWins() {
        assertEquals(RoleConventions.ADMIN, RoleConventions.strongest(List.of("viewer", "account-admin", "operator")));
        assertEquals(RoleConventions.OPERATOR, RoleConventions.strongest(List.of("viewer", "operator")));
        assertEquals(RoleConventions.VIEWER, RoleConventions.strongest(List.of()));
    }
}
