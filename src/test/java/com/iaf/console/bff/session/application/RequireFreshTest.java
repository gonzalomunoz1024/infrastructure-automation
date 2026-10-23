package com.iaf.console.bff.session.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.iaf.console.bff.session.domain.AccountMembership;
import com.iaf.console.bff.session.domain.Principal;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.bff.session.domain.StepUpPolicy;
import com.iaf.console.bff.session.domain.StepUpRequiredException;

class RequireFreshTest {

    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");
    private final Sessions sessions = new Sessions(null, null, new StepUpPolicy(Duration.ofMinutes(10), List.of("*:delete")), null, null,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private static Session authenticatedAt(Instant at) {
        return new Session("s1", new Principal("user_gonzalo", "gonzalo@example.com", "Gonzalo", List.of()),
                List.of(new AccountMembership("123456789012", "Compute", "active", "global", List.of("owners"), List.of("account-admin"))),
                "123456789012", "global", at, at, at, NOW.plus(Duration.ofHours(1)), "iss", "gonzalo", List.of("console-org-admins"), true);
    }

    @Test
    void anOrganizationWriteNeedsAFreshSignInWhateverThePatternsSay() {
        StepUpRequiredException stale = assertThrows(StepUpRequiredException.class,
                () -> sessions.requireFresh(authenticatedAt(NOW.minus(Duration.ofMinutes(11))), "guardrail:create").block());
        assertEquals("guardrail:create", stale.action());
        assertDoesNotThrow(() -> sessions.requireFresh(authenticatedAt(NOW.minus(Duration.ofMinutes(9))), "guardrail:create").block());
        assertDoesNotThrow(() -> sessions.requireFreshFor(authenticatedAt(NOW.minus(Duration.ofHours(2))), "guardrail:create").block(),
                "the patterns alone do not guard it");
    }

    @Test
    void aStepUpKeepsTheAdminFlagUntilTheProvidersGroupsSayOtherwise() {
        Session session = authenticatedAt(NOW);
        assertTrue(session.reauthenticatedAt(NOW).organizationAdmin());
        Session demoted = session.withIdpGroups(List.of("team-compute"), false);
        assertEquals(List.of("team-compute"), demoted.idpGroups());
        assertTrue(!demoted.organizationAdmin() && !demoted.switchedTo("123456789012", "global").organizationAdmin());
    }
}
