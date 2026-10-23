package com.iaf.console.bff.notifications.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.kit.events.EventEnvelope;
import com.iaf.console.platform.model.FeedEvent;
import com.iaf.console.platform.model.JobContext;
import com.iaf.console.platform.model.Person;

/** Decision 32.1: who each kind reaches, from the events, through the account's users and groups. */
class RecipientsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");
    private static final String COMPUTE = "123456789012";
    private static final String VM = "wfrn:wf:compute:global:" + COMPUTE + ":vm/vm_1";

    /** The demo's compute account: gonzalo the admin, dana and mia approve, mateo operates, mia views, and one person is suspended. */
    private static final AccountPeople PEOPLE = AccountPeople.of(COMPUTE,
            List.of(user("user_gonzalo", "active", List.of("owners")), user("user_dana", "active", List.of("members", "compute-approvers")),
                    user("user_mia", "active", List.of("viewers", "compute-approvers")), user("user_mateo", "active", List.of("members")),
                    user("user_ana", "suspended", List.of("owners"))),
            List.of(group("owners", List.of("role_admin"), List.of("user_gonzalo", "user_ana")), group("members", List.of("role_op"), List.of()),
                    group("compute-approvers", List.of(), List.of("user_dana", "user_mia"))),
            List.of(new AccountPeople.Role("role_admin", "account-admin"), new AccountPeople.Role("role_op", "operator")));

    @Test
    void aFinishedJobTellsThePersonItRanForAndABatchRowTellsNoOne() {
        Draft failed = FeedDrafts.of(job("job.finished", "user_mateo", "{\"status\":\"failed\",\"message\":\"the vCenter timed out\","
                + "\"errorClass\":\"Timeout\",\"failedStep\":\"power-off\",\"workflow\":\"vm-restart\"}"), new JobFacts("Restart vm_1", "vm-restart", null))
                .orElseThrow();
        assertEquals(NotificationKinds.JOB_FAILED, failed.kind());
        assertEquals(Notification.CRITICAL, failed.severity());
        assertEquals("Restart vm_1 failed", failed.title());
        assertTrue(failed.body().startsWith("the vCenter timed out (Timeout)."), failed.body());
        assertEquals("/jobs/job_1", failed.link());
        assertEquals(List.of("user_mateo"), ids(failed));
        Draft succeeded = FeedDrafts.of(job("job.finished", "user_mateo", "{\"status\":\"succeeded\",\"durationMs\":65000}"), null).orElseThrow();
        assertEquals(NotificationKinds.JOB_SUCCEEDED, succeeded.kind());
        assertEquals(List.of("user_mateo"), ids(succeeded));
        assertEquals(Optional.empty(), FeedDrafts.of(job("job.finished", "user_mateo", "{\"status\":\"failed\"}"), new JobFacts("Row", "vm-restart", "job_0")));
        assertEquals(Optional.empty(), FeedDrafts.of(job("job.finished", null, "{\"status\":\"failed\"}"), null), "no one to tell");
        Draft seeded = FeedDrafts.of(job("job.finished", "dana", "{\"status\":\"failed\"}"), null).orElseThrow();
        assertEquals(List.of(), ids(seeded), "a job started for someone who is no console user tells no one");
    }

    @Test
    void aPausedJobTellsItsRequesterAndAnApprovalItsGroup() {
        Draft attention = FeedDrafts.of(job("job.attention", "user_dana", "{\"step\":\"drain\",\"error\":{\"class\":\"Busy\",\"message\":\"in use\"}}"),
                null).orElseThrow();
        assertEquals(NotificationKinds.JOB_NEEDS_ATTENTION, attention.kind());
        assertEquals(List.of("user_dana"), ids(attention));
        Draft approval = FeedDrafts.of(job("job.approval-requested", "user_mateo", "{\"group\":\"compute-approvers\",\"step\":\"approve\"}"),
                new JobFacts("Restart vm_1", "vm-restart", null)).orElseThrow();
        assertEquals(NotificationKinds.APPROVAL_REQUESTED, approval.kind());
        assertEquals("Approval needed: Restart vm_1", approval.title());
        assertTrue(approval.body().endsWith(" Asked for Mateo."), approval.body());
        assertEquals(List.of("user_dana", "user_mia"), ids(approval));
        Draft decided = FeedDrafts.of(job("job.approval-decided", "user_mateo", "{\"decision\":\"rejected\",\"by\":\"user_dana\"}"), null).orElseThrow();
        assertEquals(NotificationKinds.APPROVAL_DECIDED, decided.kind());
        assertEquals(Notification.WARNING, decided.severity());
        assertEquals(List.of("user_mateo"), ids(decided));
    }

    @Test
    void anAccessRequestTellsItsApproversAndTheAdminsButNotTheAsker() {
        Draft created = ConsoleDrafts.of(console("AccessRequestChanged", "access-request", "ar_1", """
                {"version": 1, "action": "created", "snapshot": {"requestId": "ar_1", "requester": {"userId": "user_mia", "name": "Mia"},
                 "role": "operator", "approvers": ["user_dana", "user_mia"], "reason": "on call"}}""", null)).orElseThrow();
        assertEquals(NotificationKinds.ACCESS_REQUEST_CREATED, created.kind());
        assertEquals("Mia asks for access", created.title());
        assertEquals("/iam/access-requests/ar_1", created.link());
        assertEquals(List.of("user_dana", "user_gonzalo"), ids(created));
        Draft decided = ConsoleDrafts.of(console("AccessRequestChanged", "access-request", "ar_1", """
                {"version": 2, "action": "approved", "snapshot": {"requestId": "ar_1", "requester": {"userId": "user_mia"}, "decidedBy": "user_gonzalo"}}""",
                null)).orElseThrow();
        assertEquals(NotificationKinds.ACCESS_REQUEST_DECIDED, decided.kind());
        assertEquals(List.of("user_mia"), ids(decided));
    }

    @Test
    void driftQuotasAndBudgetsTellTheAdmins() {
        Draft drift = ConsoleDrafts.of(console("FindingOpened", "finding", "fnd_1", """
                {"findingId": "fnd_1", "severity": "critical", "fields": [{"path": "spec.cpus", "expected": 2, "observed": 4}]}""", VM)).orElseThrow();
        assertEquals(NotificationKinds.DRIFT_FOUND, drift.kind());
        assertEquals("/r/vm/" + VM, drift.link());
        assertEquals(List.of("user_gonzalo"), ids(drift));
        Draft quota = ConsoleDrafts.of(console("QuotaChanged", "quota", "resources.vm", """
                {"version": 3, "action": "updated", "snapshot": {"key": "resources.vm", "limit": 10, "used": 8}}""", null)).orElseThrow();
        assertEquals(NotificationKinds.QUOTA_NEAR_LIMIT, quota.kind());
        assertEquals("quota.near-limit:" + COMPUTE + ":resources.vm:10:80", quota.eventKey(), "once per quota, limit and threshold");
        assertEquals(List.of("user_gonzalo"), ids(quota));
        assertEquals(Optional.empty(), ConsoleDrafts.of(console("QuotaChanged", "quota", "resources.vm", """
                {"version": 3, "action": "updated", "snapshot": {"key": "resources.vm", "limit": 10, "used": 7}}""", null)));
        Draft full = ConsoleDrafts.of(console("QuotaChanged", "quota", "resources.vm", """
                {"version": 4, "action": "updated", "snapshot": {"key": "resources.vm", "limit": 10, "used": 10}}""", null)).orElseThrow();
        assertEquals(Notification.CRITICAL, full.severity());
        Draft budget = ConsoleDrafts.of(console("BudgetThresholdCrossed", "budget", "bud_1", """
                {"budgetId": "bud_1", "name": "compute", "threshold": 80, "percent": 82.5, "spent": 825, "amount": 1000, "currency": "USD"}""", null))
                .orElseThrow();
        assertEquals(NotificationKinds.BUDGET_ALERT, budget.kind());
        assertEquals("Budget compute passed 80%", budget.title());
        assertEquals(List.of("user_gonzalo"), ids(budget));
    }

    @Test
    void aDigestTellsEveryActiveMemberAndAnUnansweredDirectoryOnlyThePersonNamed() {
        Draft digest = new Draft("digest.ready:" + COMPUTE + ":2026-10-12", NotificationKinds.DIGEST_READY, Notification.INFO, "Ready", null, COMPUTE,
                null, null, null, "/digests/2026-10-12", NOW, List.of(Audience.members()));
        assertEquals(List.of("user_gonzalo", "user_dana", "user_mia", "user_mateo"), ids(digest), "not the suspended one");
        Draft failed = FeedDrafts.of(job("job.finished", "user_mateo", "{\"status\":\"failed\"}"), null).orElseThrow();
        assertEquals(List.of("user_mateo"), Recipients.of(failed, null).stream().map(Recipient::userId).toList());
        Draft approval = FeedDrafts.of(job("job.approval-requested", "user_mateo", "{\"group\":\"compute-approvers\"}"), null).orElseThrow();
        assertEquals(List.of(), Recipients.of(approval, null), "a group's members need the directory");
        Draft stranger = FeedDrafts.of(job("job.finished", "user_noah", "{\"status\":\"failed\"}"), null).orElseThrow();
        assertEquals(List.of(), ids(stranger), "someone the account does not hold is no one to tell");
    }

    private static List<String> ids(Draft draft) {
        return Recipients.of(draft, PEOPLE).stream().map(Recipient::userId).toList();
    }

    private static FeedEvent job(String type, String person, String data) {
        try {
            return new FeedEvent("1.0", 1, "evt_" + type, type + ":job_1:3", 3L, type, NOW, "job", "job_1", "platform",
                    person == null ? null : new Person(person, person.equals("user_mateo") ? "Mateo" : null, null),
                    new JobContext(COMPUTE, "global", VM, null, Map.of()), null, JSON.readTree(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static EventEnvelope console(String type, String aggregate, String id, String data, String wfrn) {
        try {
            JsonNode node = JSON.readTree(data);
            return EventEnvelope.of("evt_" + id, type + ":" + COMPUTE + ":" + id + ":" + node.path("version").asInt(1), type, NOW, aggregate, id, "user_x",
                    node).withContext(new EventEnvelope.Context(COMPUTE, null, wfrn, null), null);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static AccountPeople.User user(String id, String status, List<String> groups) {
        return new AccountPeople.User(id, id.substring(5) + "@example.com", null, status, groups, List.of());
    }

    private static AccountPeople.Group group(String name, List<String> roles, List<String> members) {
        return new AccountPeople.Group("grp_" + name, name, roles, members, List.of());
    }
}
