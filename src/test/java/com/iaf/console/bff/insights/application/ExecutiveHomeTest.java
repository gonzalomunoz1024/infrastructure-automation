package com.iaf.console.bff.insights.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.iaf.console.bff.access.application.Access;
import com.iaf.console.bff.access.application.AccessProperties;
import com.iaf.console.bff.access.domain.AccessCheck;
import com.iaf.console.bff.access.domain.Decision;
import com.iaf.console.bff.access.domain.Decisions;
import com.iaf.console.bff.access.domain.Denial;
import com.iaf.console.bff.access.domain.DeniedException;
import com.iaf.console.bff.insights.domain.InsightsScope;
import com.iaf.console.bff.insights.domain.RequestRecord;
import com.iaf.console.bff.platform.application.PlatformProperties;
import com.iaf.console.bff.session.domain.AccountMembership;
import com.iaf.console.bff.session.domain.Principal;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.platform.model.EvidenceEntry;
import com.iaf.console.platform.model.EvidenceRecord;
import com.iaf.console.platform.model.JobContext;
import com.iaf.console.platform.model.JobStatus;
import com.iaf.console.platform.model.JobSummary;
import com.iaf.console.platform.model.Person;

import reactor.core.publisher.Mono;

class ExecutiveHomeTest {

    private static final String COMPUTE = "123456789012";
    private static final String DATA = "210987654321";
    private static final String CLOSED = "333333333333";
    private static final Instant FROM = Instant.parse("2026-09-23T12:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-23T12:00:00Z");

    private static Session session() {
        Instant now = Instant.parse("2026-10-23T12:00:00Z");
        List<AccountMembership> accounts = List.of(
                new AccountMembership(COMPUTE, "compute", "active", "global", List.of("owners"), List.of("account-admin")),
                new AccountMembership(DATA, "data", "active", "global", List.of("viewers"), List.of("viewer")),
                new AccountMembership(CLOSED, "old", "closed", "global", List.of("owners"), List.of("account-admin")));
        return new Session("s1", new Principal("user_gonzalo", "gonzalo@example.com", "Gonzalo", List.of()), accounts, COMPUTE, "global", now, now, now,
                now.plus(Duration.ofHours(1)), "https://idp", "gonzalo");
    }

    /** An executive home whose policies allow insights:get in the accounts named, recording every check. */
    private static ExecutiveHome home(Set<String> allowedIn, List<String> asked) {
        Access access = new Access((scope, principal, groups, checks) -> {
            List<Decision> items = new ArrayList<>();
            for (AccessCheck check : checks) {
                asked.add(scope.accountId() + " " + check.action() + " " + check.resource());
                items.add(allowedIn.contains(scope.accountId()) ? Decision.allow()
                        : Decision.deny(Denial.byPolicy(check.action(), check.resource(), "No statement allows " + check.action(), null, null, null)));
            }
            return Mono.just(new Decisions(items, 1));
        }, new AccessProperties(Duration.ZERO, 10));
        PlatformProperties platform = new PlatformProperties("http://localhost:1", "", "wf", Duration.ofSeconds(1), Duration.ofMinutes(1), Map.of(), null);
        InsightsProperties properties = new InsightsProperties(null, Duration.ZERO, 10, 2);
        return new ExecutiveHome(access, null, platform, null, null, null, null, properties, Clock.systemUTC());
    }

    @Test
    void theOrganizationIsEveryOpenAccountThePersonMayReadInsightsIn() {
        List<String> asked = new ArrayList<>();
        List<AccountMembership> covered = home(Set.of(COMPUTE, DATA), asked).covered(session(), InsightsScope.ORGANIZATION).block();
        assertEquals(List.of(COMPUTE, DATA), covered.stream().map(AccountMembership::accountId).toList());
        assertEquals(List.of(COMPUTE + " insights:get wfrn:wf:insights:*:" + COMPUTE + ":*", DATA + " insights:get wfrn:wf:insights:*:" + DATA + ":*"), asked);
    }

    @Test
    void anAccountWithoutInsightsIsLeftOut() {
        List<AccountMembership> covered = home(Set.of(DATA), new ArrayList<>()).covered(session(), InsightsScope.ORGANIZATION).block();
        assertEquals(List.of(DATA), covered.stream().map(AccountMembership::accountId).toList());
    }

    @Test
    void theAccountScopeIsTheSessionsAccountOnly() {
        List<String> asked = new ArrayList<>();
        List<AccountMembership> covered = home(Set.of(COMPUTE, DATA), asked).covered(session(), InsightsScope.ACCOUNT).block();
        assertEquals(List.of(COMPUTE), covered.stream().map(AccountMembership::accountId).toList());
        assertEquals(1, asked.size());
    }

    @Test
    void noAccountAllowedIsTheSessionAccountsRefusal() {
        DeniedException denied = assertThrows(DeniedException.class,
                () -> home(Set.of(), new ArrayList<>()).covered(session(), InsightsScope.ORGANIZATION).block());
        assertEquals("insights:get", denied.denial().action());
        assertEquals("wfrn:wf:insights:*:" + COMPUTE + ":*", denied.denial().resource());
    }

    private static JobSummary job(String id, String parent, String account, Person person, Instant startedAt, JobStatus status, Map<String, String> labels) {
        return new JobSummary(id, "create-vm", "Create VM", "1", status, "sa_console", "rest", startedAt, null, 1_000L, parent, "single", null, null, null,
                null, null, person, account == null ? null : new JobContext(account, "global", null, null, labels));
    }

    @Test
    void aRequestIsATopLevelJobAPersonStartedInTheAccountInTheWindow() {
        Person dana = new Person("user_dana", "Dana", "dana@example.com");
        Instant in = FROM.plusSeconds(60);
        assertTrue(ExecutiveHome.isRequest(job("j1", null, COMPUTE, dana, in, JobStatus.SUCCEEDED, null), COMPUTE, FROM, TO));
        assertTrue(ExecutiveHome.isRequest(job("j1", null, COMPUTE, dana, FROM, JobStatus.SUCCEEDED, null), COMPUTE, FROM, TO));
        assertFalse(ExecutiveHome.isRequest(job("j2", "j1", COMPUTE, dana, in, JobStatus.SUCCEEDED, null), COMPUTE, FROM, TO), "a batch row");
        assertFalse(ExecutiveHome.isRequest(job("j3", null, DATA, dana, in, JobStatus.SUCCEEDED, null), COMPUTE, FROM, TO), "another account");
        assertFalse(ExecutiveHome.isRequest(job("j4", null, null, dana, in, JobStatus.SUCCEEDED, null), COMPUTE, FROM, TO), "no console context");
        assertFalse(ExecutiveHome.isRequest(job("j5", null, COMPUTE, null, in, JobStatus.SUCCEEDED, null), COMPUTE, FROM, TO), "no person");
        assertFalse(ExecutiveHome.isRequest(job("j6", null, COMPUTE, dana, FROM.minusSeconds(1), JobStatus.SUCCEEDED, null), COMPUTE, FROM, TO));
        assertTrue(ExecutiveHome.isConsoleRetry(job("j7", null, COMPUTE, dana, in, JobStatus.SUCCEEDED, Map.of("retry-of", "j0"))));
        assertFalse(ExecutiveHome.isConsoleRetry(job("j8", null, COMPUTE, dana, in, JobStatus.SUCCEEDED, Map.of("reason", "x"))));
    }

    @Test
    void aPersonsDecisionOnAFailedStepIsSteppingIn() {
        EvidenceEntry step = new EvidenceEntry(1, TO, "step", "sa_console", Map.of(), "h1", "h0");
        EvidenceEntry approval = new EvidenceEntry(2, TO, "approval", "user_mia", Map.of(), "h2", "h1");
        EvidenceEntry bySystem = new EvidenceEntry(3, TO, "decision", "system", Map.of("action", "retry"), "h3", "h2");
        EvidenceEntry byPerson = new EvidenceEntry(4, TO, "decision", "user_dana", Map.of("action", "skip"), "h4", "h3");
        assertFalse(ExecutiveHome.steppedIn(evidence(step, approval, bySystem)), "approvals and the system's own decisions are self-service");
        assertTrue(ExecutiveHome.steppedIn(evidence(step, byPerson)));
    }

    private static EvidenceRecord evidence(EvidenceEntry... entries) {
        return new EvidenceRecord("j1", "create-vm", "1", "sa_console", "rest", List.of(entries), "h", null, null, null, null);
    }

    @Test
    void outcomesAndTitles() {
        assertEquals(RequestRecord.Outcome.SUCCEEDED, ExecutiveHome.outcomeOf(JobStatus.SUCCEEDED));
        assertEquals(RequestRecord.Outcome.FAILED, ExecutiveHome.outcomeOf(JobStatus.FAILED));
        assertEquals(RequestRecord.Outcome.FAILED, ExecutiveHome.outcomeOf(JobStatus.ROLLED_BACK));
        assertEquals(RequestRecord.Outcome.OTHER, ExecutiveHome.outcomeOf(JobStatus.CANCELLED));
        assertEquals(RequestRecord.Outcome.OTHER, ExecutiveHome.outcomeOf(JobStatus.RUNNING));
        assertEquals("Compute", ExecutiveHome.titleOf("compute"));
        assertEquals("Object storage", ExecutiveHome.titleOf("object-storage"));
    }
}
