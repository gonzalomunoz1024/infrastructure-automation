package com.iaf.console.bff.session.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.bff.session.domain.ResolvedPrincipal;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.kit.mongo.Mongo;

/** principals:resolve's time-boxed roles, and a session with them as the Mongo store keeps it. */
class PrincipalResolutionTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void theEarliestElevationEndOfAnAccountIsKept() throws Exception {
        ResolvedPrincipal resolved = HttpPrincipalResolver.parse(JSON.readTree("""
                {"user":{"userId":"user_dana","email":"dana@example.com"},
                 "accounts":[
                   {"accountId":"123456789012","groups":["members"],"roles":["operator","account-admin"],
                    "elevations":[{"elevationId":"elev_2","role":"account-admin","kind":"elevation","until":"2026-10-23T10:00:00Z"},
                                  {"elevationId":"elev_1","role":"auditor","kind":"break-glass","until":"2026-10-23T09:00:00Z"}]},
                   {"accountId":"210987654321","groups":["viewers"],"roles":["viewer"]}]}
                """));
        assertEquals(Instant.parse("2026-10-23T09:00:00Z"), resolved.accounts().get(0).elevatedUntil());
        assertNull(resolved.accounts().get(1).elevatedUntil());
        assertEquals(Instant.parse("2026-10-23T09:00:00Z"), Session.earliestElevationEnd(resolved.accounts()));
    }

    @Test
    void anUnreadableEndCountsAsEnded() throws Exception {
        ResolvedPrincipal resolved = HttpPrincipalResolver.parse(JSON.readTree("""
                {"user":{"userId":"user_dana","email":"dana@example.com"},
                 "accounts":[{"accountId":"123456789012","groups":[],"roles":["account-admin"],
                              "elevations":[{"elevationId":"elev_1","role":"account-admin","kind":"elevation","until":"soon"}]}]}
                """));
        assertEquals(Instant.EPOCH, resolved.accounts().get(0).elevatedUntil());
    }

    @Test
    void aSessionWithItsRereadFieldsSurvivesTheStoresMapper() throws Exception {
        ResolvedPrincipal resolved = HttpPrincipalResolver.parse(JSON.readTree("""
                {"user":{"userId":"user_dana","email":"dana@example.com"},
                 "accounts":[{"accountId":"123456789012","groups":["members"],"roles":["operator"],
                              "elevations":[{"elevationId":"elev_1","role":"account-admin","kind":"elevation","until":"2026-10-23T09:00:00Z"}]}]}
                """));
        Instant t0 = Instant.parse("2026-10-23T08:00:00Z");
        Session session = new Session("s1", resolved.user(), resolved.accounts(), "123456789012", "global", t0, t0, t0, t0.plus(Duration.ofHours(12)),
                "https://idp.example", "dana", List.of("idp-group"), t0, t0.plusSeconds(300));
        ObjectMapper mapper = Mongo.mapper();
        Session read = mapper.readValue(mapper.writeValueAsString(session), Session.class);
        assertEquals(session, read);
    }
}
