package com.iaf.console.bff.access.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class ManagedRolesTest {

    @Test
    void aViewerReads() {
        assertTrue(ManagedRoles.allows(ManagedRoles.VIEWER, "compute:get"));
        assertTrue(ManagedRoles.allows(ManagedRoles.VIEWER, "compute:list"));
        assertTrue(ManagedRoles.allows(ManagedRoles.VIEWER, "compute:preview"));
        assertTrue(ManagedRoles.allows(ManagedRoles.VIEWER, "compute:observe"), "a resource's metrics are a read, as the identity service's viewer policy has it");
        assertTrue(ManagedRoles.allows(ManagedRoles.VIEWER, "jobs:list"));
        assertFalse(ManagedRoles.allows(ManagedRoles.VIEWER, "compute:create"));
        assertFalse(ManagedRoles.allows(ManagedRoles.VIEWER, "jobs:cancel"));
        assertFalse(ManagedRoles.allows(ManagedRoles.VIEWER, "approvals:decide"));
    }

    @Test
    void anOperatorChangesButNeverDestroysOrAdministers() {
        assertTrue(ManagedRoles.allows(ManagedRoles.OPERATOR, "compute:create"));
        assertTrue(ManagedRoles.allows(ManagedRoles.OPERATOR, "compute:restart"));
        assertTrue(ManagedRoles.allows(ManagedRoles.OPERATOR, "jobs:cancel"));
        assertTrue(ManagedRoles.allows(ManagedRoles.OPERATOR, "approvals:decide"));
        assertFalse(ManagedRoles.allows(ManagedRoles.OPERATOR, "compute:delete"));
        assertFalse(ManagedRoles.allows(ManagedRoles.OPERATOR, "data:terminate"));
        assertFalse(ManagedRoles.allows(ManagedRoles.OPERATOR, "iam:create"));
        assertFalse(ManagedRoles.allows(ManagedRoles.OPERATOR, "iam:decide"));
    }

    @Test
    void anAdminDoesEverythingAndAnAuditorReadsTheTrail() {
        assertTrue(ManagedRoles.allows(ManagedRoles.ADMIN, "compute:delete"));
        assertTrue(ManagedRoles.allows(ManagedRoles.ADMIN, "iam:create"));
        assertTrue(ManagedRoles.allows(ManagedRoles.AUDITOR, "audit:export"));
        assertTrue(ManagedRoles.allows(ManagedRoles.AUDITOR, "compute:get"));
        assertFalse(ManagedRoles.allows(ManagedRoles.AUDITOR, "compute:create"));
        assertTrue(ManagedRoles.anyAllows(List.of("viewer", "operator"), "compute:create"));
        assertFalse(ManagedRoles.anyAllows(List.of("viewer"), "compute:create"));
    }

    @Test
    void onlyAdminsAndAuditorsReadTheAccessRequestQueueAndOnlyAdminsDecide() {
        assertFalse(ManagedRoles.allows(ManagedRoles.VIEWER, "access-request:list"));
        assertFalse(ManagedRoles.allows(ManagedRoles.OPERATOR, "access-request:get"));
        assertTrue(ManagedRoles.allows(ManagedRoles.AUDITOR, "access-request:list"));
        assertTrue(ManagedRoles.allows(ManagedRoles.ADMIN, "access-request:get"));
        assertFalse(ManagedRoles.allows(ManagedRoles.OPERATOR, "access-request:decide"));
        assertFalse(ManagedRoles.allows(ManagedRoles.AUDITOR, "access-request:decide"));
        assertEquals(ManagedRoles.AUDITOR, ManagedRoles.suggestedFor("access-request:list"));
        assertEquals(ManagedRoles.ADMIN, ManagedRoles.suggestedFor("access-request:decide"));
    }

    @Test
    void theSuggestedRoleIsTheWeakestThatAllows() {
        assertEquals(ManagedRoles.VIEWER, ManagedRoles.suggestedFor("compute:get"));
        assertEquals(ManagedRoles.OPERATOR, ManagedRoles.suggestedFor("compute:create"));
        assertEquals(ManagedRoles.ADMIN, ManagedRoles.suggestedFor("compute:delete"));
        assertEquals(ManagedRoles.ADMIN, ManagedRoles.suggestedFor("iam:update"));
        assertNotNull(ManagedRoles.statementFor(ManagedRoles.OPERATOR, "compute:create"));
    }

    @Test
    void denialsNameTheLayer() {
        Denial policy = Denial.byPolicy("compute:delete", "wfrn:wf:compute:global:123456789012:vm/*", "no", "operator", "managed/operator", "AllowChanges");
        assertEquals(Denial.CONSOLE_IAM, policy.layer());
        assertTrue(policy.requestable());
        assertEquals(ManagedRoles.ADMIN, policy.suggestedRole());
        Denial platform = Denial.byPlatform("compute:delete", "wfrn:wf:compute:global:123456789012:vm/vm-1", "scope missing", "workflows:run");
        assertEquals(Denial.PLATFORM, platform.layer());
        assertEquals("workflows:run", platform.scope());
        assertFalse(platform.requestable());
    }

    @Test
    void accessChecksSpellTheWfrnPatterns() {
        assertEquals("compute:create", AccessCheck.action("compute", "create"));
        assertEquals("wfrn:wf:compute:global:123456789012:vm/*", AccessCheck.typePattern("wf", "compute", "global", "123456789012", "vm"));
        assertEquals("wfrn:wf:jobs:global:123456789012:*", AccessCheck.servicePattern("wf", "jobs", "global", "123456789012"));
    }
}
