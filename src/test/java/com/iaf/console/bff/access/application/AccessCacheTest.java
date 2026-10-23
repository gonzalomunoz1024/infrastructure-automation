package com.iaf.console.bff.access.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.iaf.console.bff.access.domain.AccessCheck;
import com.iaf.console.bff.access.domain.Decision;
import com.iaf.console.bff.access.domain.Decisions;
import com.iaf.console.bff.access.domain.Denial;
import com.iaf.console.bff.access.ports.outbound.AuthorizationPort;
import com.iaf.console.bff.session.domain.AccountMembership;
import com.iaf.console.bff.session.domain.Principal;
import com.iaf.console.bff.session.domain.Session;

import reactor.core.publisher.Mono;

/** Review finding 6: a decision is reused only for the same context: account, region, person and groups. */
class AccessCacheTest {

    private static final String ACCOUNT = "123456789012";
    private static final String ACTION = "compute:create";
    private static final String RESOURCE = "wfrn:wf:compute:eu-west-1:123456789012:vm/*";

    private final List<String> asked = new ArrayList<>();
    // a guardrail denies everything outside eu-west-1
    private final AuthorizationPort port = (scope, principal, groups, checks) -> {
        asked.add(scope.region() + " " + groups);
        List<Decision> out = new ArrayList<>();
        for (AccessCheck check : checks) {
            out.add("eu-west-1".equals(scope.region()) && groups.contains("members") ? Decision.allow()
                    : Decision.deny(Denial.byPolicy(check.action(), check.resource(), "denied by a guardrail", null, null, null)));
        }
        return Mono.just(new Decisions(out, 1));
    };
    private final Access access = new Access(port, new AccessProperties(Duration.ofSeconds(60), 1000));

    @Test
    void switchingRegionDecidesAgain() {
        Session session = session("eu-west-1", List.of("members"));
        assertTrue(decide(session).allowed());
        assertTrue(decide(session).allowed());
        assertEquals(1, asked.size(), "the same context is cached");
        assertFalse(decide(session.switchedTo(ACCOUNT, "us-east-1")).allowed(), "another region is another decision");
        assertEquals(2, asked.size());
    }

    @Test
    void otherGroupsDecideAgain() {
        assertTrue(decide(session("eu-west-1", List.of("members"))).allowed());
        assertFalse(decide(session("eu-west-1", List.of("viewers"))).allowed());
        assertEquals(2, asked.size());
    }

    private Decision decide(Session session) {
        return access.decide(session, List.of(new AccessCheck(ACTION, RESOURCE))).block().get(0);
    }

    private static Session session(String region, List<String> groups) {
        Instant t0 = Instant.parse("2026-10-23T08:00:00Z");
        return new Session("s1", new Principal("user_dana", "dana@example.com", "Dana", List.of()),
                List.of(new AccountMembership(ACCOUNT, "Compute", "active", region, groups, List.of("operator"))), ACCOUNT, region, t0, t0, t0,
                t0.plus(Duration.ofHours(12)), "https://idp.example", "dana");
    }
}
