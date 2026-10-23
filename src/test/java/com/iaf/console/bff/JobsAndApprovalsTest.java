package com.iaf.console.bff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.bff.platform.application.PlatformClients;
import com.iaf.console.bff.trust.application.Trust;

/** The Requests page: the account's jobs, one job, cancel, and the approvals with the decision. */
class JobsAndApprovalsTest extends DemoApp {

    @Autowired
    PlatformClients clients;

    @Autowired
    Trust trust;

    @Test
    void theAccountsJobsAreListedWithTheirResources() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        Console.Reply reply = console.get("/console/v1/jobs?limit=50", gonzalo);
        assertEquals(200, reply.status(), reply.body());
        JsonNode items = reply.json().path("items");
        assertTrue(items.size() > 0, reply.body());
        for (JsonNode job : items) {
            assertFalse(job.path("jobId").asText().isBlank());
            assertFalse(job.path("workflow").asText().isBlank());
            assertFalse(job.path("status").asText().isBlank());
            assertTrue(Set.of("single", "batch").contains(job.path("kind").asText()));
        }
        Console.Reply filtered = console.get("/console/v1/jobs?workflow=create-vm", gonzalo);
        assertTrue(filtered.json().path("items").size() > 0);
        filtered.json().path("items").forEach(job -> assertEquals("create-vm", job.path("workflow").asText()));
    }

    @Test
    void oneJobShowsItsStepsAndWhatMayBeDone() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        String jobId = console.get("/console/v1/jobs?workflow=create-vm", gonzalo).json().path("items").get(0).path("jobId").asText();
        Console.Reply reply = console.get("/console/v1/jobs/" + jobId, gonzalo);
        assertEquals(200, reply.status(), reply.body());
        JsonNode job = reply.json();
        assertEquals(jobId, job.path("jobId").asText());
        assertTrue(job.path("steps").size() > 0, reply.body());
        JsonNode step = job.path("steps").get(0);
        assertFalse(step.path("name").asText().isBlank());
        assertFalse(step.path("status").asText().isBlank());
        assertTrue(job.path("can").toString().contains("rerun"), job.path("can").toString());
        assertTrue(job.has("input"));
    }

    @Test
    void aJobOfAnotherAccountIs404() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        String computeJob = console.get("/console/v1/jobs?workflow=create-vm", gonzalo).json().path("items").get(0).path("jobId").asText();
        Console.Signed raj = console.signIn("raj");
        assertEquals(DATA, raj.accountId());
        assertEquals(404, console.get("/console/v1/jobs/" + computeJob, raj).status());
        assertEquals(404, console.get("/console/v1/jobs/job_does_not_exist", gonzalo).status());
        assertEquals(404, console.post("/console/v1/jobs/" + computeJob + ":cancel", raj, null).status(),
                "a job outside the account is invisible, even to an operator who may cancel");
    }

    @Test
    void aViewerMayNotCancel() {
        Console.Signed mia = console.signIn("mia");
        String jobId = console.get("/console/v1/jobs?workflow=create-vm", mia).json().path("items").get(0).path("jobId").asText();
        Console.Reply reply = console.post("/console/v1/jobs/" + jobId + ":cancel", mia, null);
        assertEquals(403, reply.status(), reply.body());
        JsonNode denied = reply.json().path("denied");
        assertEquals("console-iam", denied.path("layer").asText());
        assertEquals("jobs:cancel", denied.path("action").asText());
        assertEquals("viewer", denied.path("role").asText());
        assertNotNull(reply.json().path("correlationId").asText(null));
    }

    @Test
    void anApprovalIsDecidedByAnApproverWhosePoliciesAllowIt() {
        String jobId = startVmRestart(clients, trust);
        Console.Signed dana = console.signIn("dana");
        Console.Signed mia = console.signIn("mia");
        Console.Signed gonzalo = console.signIn("gonzalo");
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .until(() -> pending(dana, jobId) != null);
        JsonNode forDana = pending(dana, jobId);
        assertEquals("compute-approvers", forDana.path("group").asText());
        assertEquals("vm-restart", forDana.path("workflow").asText());
        assertTrue(forDana.path("canDecide").asBoolean(), "dana is an operator in compute-approvers");
        assertFalse(pending(mia, jobId).path("canDecide").asBoolean(true), "mia is in the group but a viewer");
        assertFalse(pending(gonzalo, jobId).path("canDecide").asBoolean(true), "gonzalo is an admin but not in the group");
        String approvalId = forDana.path("approvalId").asText();
        Console.Reply job = console.get("/console/v1/jobs/" + jobId, dana);
        assertEquals(200, job.status(), job.body());
        assertTrue(job.json().path("approvals").toString().contains(approvalId), job.body());
        Console.Reply refused = console.post("/console/v1/approvals/" + approvalId + ":decide", mia, Map.of("decision", "approve"));
        assertEquals(403, refused.status(), refused.body());
        assertEquals("approvals:decide", refused.json().path("denied").path("action").asText());
        Console.Reply badDecision = console.post("/console/v1/approvals/" + approvalId + ":decide", dana, Map.of("decision", "maybe"));
        assertEquals(400, badDecision.status(), badDecision.body());
        Console.Reply approved = console.post("/console/v1/approvals/" + approvalId + ":decide", dana, Map.of("decision", "approve", "comment", "go"));
        assertEquals(202, approved.status(), approved.body());
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250))
                .until(() -> Set.of("succeeded", "failed").contains(console.get("/console/v1/jobs/" + jobId, dana).json().path("status").asText()));
        assertEquals("succeeded", console.get("/console/v1/jobs/" + jobId, dana).json().path("status").asText());
        Console.Reply decided = console.get("/console/v1/approvals?status=approved", dana);
        assertEquals(200, decided.status());
        assertEquals(404, console.post("/console/v1/approvals/apr_nope:decide", dana, Map.of("decision", "reject")).status());
    }

    private static JsonNode pending(Console.Signed as, String jobId) {
        Console.Reply reply = console.get("/console/v1/approvals?status=pending", as);
        assertEquals(200, reply.status(), reply.body());
        for (JsonNode approval : reply.json().path("items")) {
            if (jobId.equals(approval.path("jobId").asText())) {
                return approval;
            }
        }
        return null;
    }
}
