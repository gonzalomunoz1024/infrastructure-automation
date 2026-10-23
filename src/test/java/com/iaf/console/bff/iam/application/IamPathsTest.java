package com.iaf.console.bff.iam.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

class IamPathsTest {

    @Test
    void theAccountsGuardrailsAreReadOnlyAndTheSimulatorTakesAPostOnly() {
        assertNull(IamPaths.refusal(HttpMethod.GET, "guardrails"));
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, IamPaths.refusal(HttpMethod.POST, "guardrails"));
        assertEquals(HttpStatus.NOT_FOUND, IamPaths.refusal(HttpMethod.GET, "guardrails/gr_1"));
        assertNull(IamPaths.refusal(HttpMethod.POST, "policy-simulations"));
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, IamPaths.refusal(HttpMethod.GET, "policy-simulations"));
        assertEquals(HttpStatus.NOT_FOUND, IamPaths.refusal(HttpMethod.POST, "policy-simulations/x"));
    }

    @Test
    void theRulesAreTheOrganizationsAndReadOnly() {
        assertNull(IamPaths.refusal(HttpMethod.GET, "quota-rules"));
        assertNull(IamPaths.refusal(HttpMethod.GET, "elevation-rules"));
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, IamPaths.refusal(HttpMethod.PUT, "elevation-rules"));
        assertTrue(IamPaths.global("quota-rules"));
        assertTrue(IamPaths.global("elevation-rules"));
        assertFalse(IamPaths.global("quotas"));
        assertFalse(IamPaths.global("elevations"));
    }

    @Test
    void accessReviewsHaveTheirItemsAndActions() {
        assertNull(IamPaths.refusal(HttpMethod.GET, "access-reviews"));
        assertNull(IamPaths.refusal(HttpMethod.POST, "access-reviews"));
        assertNull(IamPaths.refusal(HttpMethod.GET, "access-reviews/arev_1"));
        assertNull(IamPaths.refusal(HttpMethod.POST, "access-reviews/arev_1:cancel"));
        assertNull(IamPaths.refusal(HttpMethod.GET, "access-reviews/arev_1/items"));
        assertNull(IamPaths.refusal(HttpMethod.POST, "access-reviews/arev_1/items/arevi_2:decide"));
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, IamPaths.refusal(HttpMethod.DELETE, "access-reviews/arev_1"));
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, IamPaths.refusal(HttpMethod.POST, "access-reviews/arev_1/items"));
        assertEquals(HttpStatus.NOT_FOUND, IamPaths.refusal(HttpMethod.POST, "access-reviews/arev_1:close"));
        assertEquals(HttpStatus.NOT_FOUND, IamPaths.refusal(HttpMethod.GET, "access-reviews/arev_1/items/arevi_2"));
        assertEquals(HttpStatus.NOT_FOUND, IamPaths.refusal(HttpMethod.POST, "access-reviews/arev_1/items/:decide"));
        assertEquals(HttpStatus.NOT_FOUND, IamPaths.refusal(HttpMethod.GET, "access-reviews/arev_1/others"));
    }

    @Test
    void elevationsAreAskedDecidedAndRevoked() {
        assertNull(IamPaths.refusal(HttpMethod.GET, "elevations"));
        assertNull(IamPaths.refusal(HttpMethod.POST, "elevations"));
        assertNull(IamPaths.refusal(HttpMethod.GET, "elevations/elev_1"));
        assertNull(IamPaths.refusal(HttpMethod.POST, "elevations/elev_1:decide"));
        assertNull(IamPaths.refusal(HttpMethod.POST, "elevations/elev_1:revoke"));
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, IamPaths.refusal(HttpMethod.PATCH, "elevations/elev_1"));
        assertEquals(HttpStatus.NOT_FOUND, IamPaths.refusal(HttpMethod.POST, "elevations/elev_1:extend"));
        assertEquals(HttpStatus.NOT_FOUND, IamPaths.refusal(HttpMethod.POST, "elevations/:decide"));
        assertEquals(HttpStatus.NOT_FOUND, IamPaths.refusal(HttpMethod.GET, "elevations/elev_1/history"));
    }

    @Test
    void thePhaseOneCollectionsPassThroughAndUnknownOnesAreNotFound() {
        assertNull(IamPaths.refusal(HttpMethod.DELETE, "users/user_mia"));
        assertNull(IamPaths.refusal(HttpMethod.POST, "access-keys/ck_1:rotate"));
        assertEquals(HttpStatus.NOT_FOUND, IamPaths.refusal(HttpMethod.GET, "toasters"));
        assertEquals("access-reviews", IamPaths.collectionOf("access-reviews/arev_1/items"));
        assertEquals("elevations", IamPaths.collectionOf("elevations:x"));
    }
}
