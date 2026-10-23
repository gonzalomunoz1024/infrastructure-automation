package com.iaf.console.bff.digests.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.bff.insights.domain.DriftNumbers;
import com.iaf.console.bff.insights.domain.RequestRecord;

/** Decision 32.4: a digest's facts and the text the rules write of them. */
class DigestTemplateTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LocalDate WEEK = LocalDate.parse("2026-10-12");
    private static final Instant MONDAY = Instant.parse("2026-10-12T00:00:00Z");
    private static final Instant WRITTEN = Instant.parse("2026-10-19T06:00:03Z");

    @Test
    void theWeeksRequestsAreCountedAndTheBusiestWorkflowsNamed() {
        DigestFacts.Changes changes = DigestFacts.Changes.of(List.of(request("job_1", "vm-restart", RequestRecord.Outcome.SUCCEEDED, true, 1),
                request("job_2", "vm-restart", RequestRecord.Outcome.SUCCEEDED, true, 2), request("job_3", "create-vm", RequestRecord.Outcome.FAILED, false, 3),
                request("job_4", "vm-restart", RequestRecord.Outcome.SUCCEEDED, false, 4), request("job_5", "update-vm", RequestRecord.Outcome.OTHER, false, 5)));
        assertEquals(5, changes.requests());
        assertEquals(2, changes.selfServed());
        assertEquals(1, changes.failed());
        assertEquals(0.4, changes.selfServedShare());
        assertEquals(List.of(new DigestFacts.Busiest("vm-restart", 3), new DigestFacts.Busiest("create-vm", 1), new DigestFacts.Busiest("update-vm", 1)),
                changes.busiest());
        assertEquals(List.of(new DigestFacts.Failure("job_3", "create-vm")), changes.failures());
        DigestText text = DigestTemplate.write(facts(changes, DigestFacts.Drift.of(new DriftNumbers(3, 2, 5, 40d)), cost(), access()));
        assertEquals("5 requests were started through the console: 2 (40%) self-served and 1 failed. The busiest workflows were vm-restart (3), "
                + "create-vm (1) and update-vm (1).", text.texts().get("changes"));
        assertEquals("3 drift findings were found and 2 fixed; 5 findings are open at the end of the week.", text.texts().get("drift"));
        assertEquals("The account spent $1,412.50, up 25% on the week before ($1,130.00). Most of it went to Compute ($1,200.00) and Storage ($212.50).",
                text.texts().get("cost"));
        assertEquals("2 group memberships granted, 1 access key created and 1 policy now allowing every action (*).", text.texts().get("access"));
        assertEquals("5 requests, 40% self-served, 1 failed; 3 drift findings found, 5 open; spend $1,412.50, up 25%; 4 access grants.", text.headline());
    }

    @Test
    void aSectionWithoutItsSourceSaysWhyAndTheHeadlineLeavesItOut() {
        DigestFacts facts = facts(DigestFacts.Changes.of(List.of()), DigestFacts.Drift.unavailable("the console runs without a drift service"),
                DigestFacts.Cost.unavailable("the cost service did not answer"),
                DigestFacts.Access.unavailable("the console does not follow the identity service's events here"));
        DigestText text = DigestTemplate.write(facts);
        assertEquals("No requests were started through the console in the week of 2026-10-12.", text.texts().get("changes"));
        assertEquals("Drift is not in this digest: the console runs without a drift service.", text.texts().get("drift"));
        assertEquals("Spend is not in this digest: the cost service did not answer.", text.texts().get("cost"));
        assertEquals("Access changes are not in this digest: the console does not follow the identity service's events here.", text.texts().get("access"));
        assertEquals("No requests through the console.", text.headline());
        Digest digest = DigestComposer.compose(facts, text, Digest.RULES, WRITTEN);
        assertEquals(List.of("changes", "drift", "cost", "access"), digest.sections().stream().map(Digest.Section::kind).toList());
        assertEquals(Map.of("available", false, "reason", "the cost service did not answer"), digest.sections().get(2).facts());
        assertEquals(LocalDate.parse("2026-10-18"), digest.weekEnd());
    }

    @Test
    void theComposerKeepsTheFactsAndHighlightsAndMakesUpForAWriterThatLeftSomethingOut() {
        DigestFacts facts = facts(DigestFacts.Changes.of(List.of(request("job_3", "create-vm", RequestRecord.Outcome.FAILED, false, 3))),
                DigestFacts.Drift.of(new DriftNumbers(0, 0, 0, null)), cost(), access());
        Digest digest = DigestComposer.compose(facts, new DigestText("A busy week.", Map.of("cost", "Spend rose.")), Digest.ASSISTANT, WRITTEN);
        assertEquals("A busy week.", digest.headline());
        assertEquals(Digest.ASSISTANT, digest.source());
        Digest.Section changes = digest.sections().get(0);
        assertEquals(DigestTemplate.write(facts).texts().get("changes"), changes.text(), "the template writes what the writer left out");
        assertEquals(1L, changes.facts().get("requests"));
        assertEquals(List.of(new Digest.Highlight("create-vm failed", "/jobs/job_3")), changes.highlights());
        assertEquals("No drift was found, and no findings are open.", digest.sections().get(1).text());
        Digest.Section cost = digest.sections().get(2);
        assertEquals("Spend rose.", cost.text());
        assertEquals(1412.5, cost.facts().get("spend"));
        assertEquals(0.25, cost.facts().get("change"));
        assertEquals(List.of(new Digest.Highlight("Compute: $1,200.00", null), new Digest.Highlight("Storage: $212.50", null)), cost.highlights());
        Digest.Section access = digest.sections().get(3);
        assertEquals(2L, access.facts().get("membershipsGranted"));
        assertEquals(false, access.facts().get("partial"));
        assertEquals("user_raj joined data-approvers (by user_gonzalo)", access.highlights().get(0).text());
        assertEquals(new DigestSummary(WEEK, "A busy week.", WRITTEN), digest.summary());
    }

    @Test
    void aWeekTheBffBeganFollowingInIsPartial() {
        DigestFacts.Access late = DigestFacts.Access.of(MONDAY.plusSeconds(86_400 * 3), List.of());
        assertTrue(late.partial(WEEK));
        assertFalse(DigestFacts.Access.of(MONDAY.minusSeconds(1), List.of()).partial(WEEK));
        String text = DigestTemplate.write(facts(DigestFacts.Changes.of(List.of()), DigestFacts.Drift.of(new DriftNumbers(0, 0, 0, null)), cost(), late))
                .texts().get("access");
        assertTrue(text.startsWith("No one joined a group"), text);
        assertTrue(text.endsWith("since 2026-10-15T00:00:00Z, so changes before then are not counted."), text);
    }

    @Test
    void weeksStartOnMondays() {
        assertEquals(WEEK, DigestWeeks.weekOf(Instant.parse("2026-10-18T23:59:59Z")));
        assertEquals(WEEK, DigestWeeks.weekBefore(Instant.parse("2026-10-19T06:00:00Z")));
        assertEquals(Instant.parse("2026-10-19T00:00:00Z"), DigestWeeks.end(WEEK));
        assertEquals(WEEK, DigestWeeks.parse("2026-10-12"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> DigestWeeks.parse("2026-10-13"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> DigestWeeks.parse("last week"));
    }

    private static DigestFacts facts(DigestFacts.Changes changes, DigestFacts.Drift drift, DigestFacts.Cost cost, DigestFacts.Access access) {
        return new DigestFacts("123456789012", "Compute", WEEK, changes, drift, cost, access);
    }

    private static DigestFacts.Cost cost() {
        try {
            return DigestFacts.Cost.of(JSON.readTree("""
                    {"currency": "USD", "total": 1412.5, "groups": [{"key": "compute", "label": "Compute", "total": 1200},
                     {"key": "storage", "label": "Storage", "total": 212.5}, {"key": "__other", "label": "Other", "total": 0}]}"""),
                    JSON.readTree("{\"currency\": \"USD\", \"total\": 1130}"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static DigestFacts.Access access() {
        Instant at = MONDAY.plusSeconds(3600);
        return DigestFacts.Access.of(MONDAY.minusSeconds(86_400 * 30), List.of(
                new AccessFact(AccessFact.MEMBERSHIP, "user_raj", "data-approvers", "user_gonzalo", at),
                new AccessFact(AccessFact.MEMBERSHIP, "user_raj", "data-approvers", "user_gonzalo", at.plusSeconds(1)),
                new AccessFact(AccessFact.MEMBERSHIP, "user_mia", "members", "user_gonzalo", at.plusSeconds(2)),
                new AccessFact(AccessFact.ACCESS_KEY, "ck_1", "user_mateo", "user_mateo", at.plusSeconds(3)),
                new AccessFact(AccessFact.WILDCARD_POLICY, "pol_1", "allow-all", "user_gonzalo", at.plusSeconds(4))));
    }

    private static RequestRecord request(String jobId, String workflow, RequestRecord.Outcome outcome, boolean selfServed, int hour) {
        return new RequestRecord("123456789012", jobId, workflow, "user_dana", outcome, MONDAY.plusSeconds(3600L * hour), 60_000L, selfServed);
    }
}
