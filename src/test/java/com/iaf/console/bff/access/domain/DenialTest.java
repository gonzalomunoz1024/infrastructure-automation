package com.iaf.console.bff.access.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DenialTest {

    @Test
    void aGuardrailsOrABoundarysRefusalIsNeverRequestableAndSuggestsNoRole() {
        Denial guardrail = Denial.decided("compute:delete", "wfrn:wf:compute:global:123456789012:vm/*", "the guardrail refuses it", "account-admin",
                null, "NoDeletes", Denial.BY_GUARDRAIL, "gr_1", true, "account-admin");
        assertEquals(Denial.CONSOLE_IAM, guardrail.layer());
        assertEquals("guardrail", guardrail.refusedBy());
        assertEquals("gr_1", guardrail.guardrailId());
        assertFalse(guardrail.requestable());
        assertNull(guardrail.suggestedRole());
        assertNull(guardrail.scope(), "scope stays the platform's");
        Denial boundary = Denial.decided("iam:create", "r", "the boundary refuses it", "operator", "pol_b", null, Denial.BY_BOUNDARY, null, null, null);
        assertFalse(boundary.requestable());
        assertEquals("pol_b", boundary.policyId());
    }

    @Test
    void aPolicysRefusalKeepsWhatTheIdentityServiceSaid() {
        Denial asked = Denial.decided("iam:create", "r", "no", "viewer", "managed/viewer", "AllowReads", Denial.BY_POLICY, null, null, null);
        assertTrue(asked.requestable(), "requestable unless the identity service said otherwise");
        assertEquals("account-admin", asked.suggestedRole(), "the managed role that would allow it");
        assertEquals("policy", asked.refusedBy());
        Denial own = Denial.decided("access-request:decide", "r", "nobody decides their own", null, null, null, Denial.BY_POLICY, null, false, null);
        assertFalse(own.requestable());
        Denial unknown = Denial.decided("iam:create", "r", "no", null, null, null, "toaster", null, null, "operator");
        assertNull(unknown.refusedBy(), "only policy, boundary and guardrail are names of what refused");
        assertEquals("operator", unknown.suggestedRole());
    }

    @Test
    void theCanonicalConstructorRefusesARequestableBoundary() {
        Denial d = new Denial(Denial.CONSOLE_IAM, "iam:create", "r", "no", null, null, null, null, Denial.BY_BOUNDARY, null, true, null);
        assertFalse(d.requestable());
        assertEquals("viewer", d.withRole("viewer").role());
        assertEquals("boundary", d.withRole("viewer").refusedBy());
    }
}
