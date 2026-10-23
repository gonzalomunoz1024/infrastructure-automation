package com.iaf.console.drift.journey;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.drift.drift.application.FindingEvents;
import com.iaf.console.drift.drift.application.WebhookService;
import com.iaf.console.drift.drift.domain.Json;
import com.iaf.console.kit.outbox.InMemoryOutbox;
import com.iaf.console.kit.outbox.OutboxEntry;
import com.iaf.console.kit.outbox.OutboxPort;

/** The plan's drift journeys on the demo: a change at the provider, revert, accept, ignore, discovery and import, webhooks, isolation. */
class DriftJourneyTest extends DemoServiceTest {

    @Autowired
    private OutboxPort outbox;

    private List<JsonNode> eventsOf(String findingId) {
        List<JsonNode> out = new ArrayList<>();
        for (OutboxEntry e : ((InMemoryOutbox) outbox).all().collectList().block()) {
            if (FindingEvents.TOPIC.equals(e.topic()) && findingId.equals(e.event().aggregateId())) {
                out.add(Json.MAPPER.valueToTree(e.event()));
            }
        }
        return out;
    }

    @Test
    void aChangeAtTheProviderOpensAFindingAndARevertPutsItBack() {
        JsonNode vm = vm("api-01");
        String wfrn = vm.path("wfrn").asText();
        setAtProvider(vm.path("providerId").asText(), "memoryGb", 32);

        JsonNode finding = openFinding(wfrn);
        ContractSchemas.assertValid(ContractSchemas.FINDING, finding, "the finding");
        assertThat(finding.path("severity").asText()).isEqualTo("warning");
        assertThat(finding.path("fields")).hasSize(1);
        assertThat(finding.path("fields").get(0).path("path").asText()).isEqualTo("/memoryGb");
        assertThat(finding.path("fields").get(0).path("expected").asInt()).isEqualTo(8);
        assertThat(finding.path("fields").get(0).path("observed").asInt()).isEqualTo(32);
        String findingId = finding.path("findingId").asText();

        JsonNode reverted = body(dana().post().uri("/v1/findings/" + findingId + ":revert").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "revert-api-01").bodyValue("{\"reason\":\"put it back\"}").exchange().expectStatus().isAccepted());
        assertThat(reverted.path("findingId").asText()).isEqualTo(findingId);
        assertThat(reverted.path("jobId").asText()).startsWith("job_");

        JsonNode closed = await(() -> finding(findingId), f -> "closed".equals(f.path("status").asText()), "the revert to close the finding");
        ContractSchemas.assertValid(ContractSchemas.FINDING, closed, "the closed finding");
        assertThat(closed.path("resolution").asText()).isEqualTo("reverted");
        assertThat(closed.path("decidedBy").asText()).isEqualTo("dana");
        assertThat(closed.path("revertJobId").asText()).isEqualTo(reverted.path("jobId").asText());

        List<JsonNode> events = eventsOf(findingId);
        assertThat(events).extracting(e -> e.path("type").asText())
                .containsSubsequence(FindingEvents.OPENED, FindingEvents.UPDATED, FindingEvents.CLOSED);
        for (JsonNode e : events) {
            ContractSchemas.assertValid(ContractSchemas.FINDING_EVENT, e, e.path("type").asText());
            assertThat(e.path("context").path("wfrn").asText()).isEqualTo(wfrn);
            assertThat(e.path("key").asText()).isEqualTo(e.path("type").asText() + ":" + wfrn + ":" + findingId + ":" + e.path("data").path("version").asLong());
        }
    }

    @Test
    void acceptMakesTheObservedValuesTheDesiredOnes() {
        JsonNode vm = vm("web-01");
        String wfrn = vm.path("wfrn").asText();
        setAtProvider(vm.path("providerId").asText(), "cpu", 8);
        JsonNode finding = openFinding(wfrn);
        // production: critical
        assertThat(finding.path("severity").asText()).isEqualTo("critical");

        JsonNode accepted = body(dana().post().uri("/v1/findings/" + finding.path("findingId").asText() + ":accept")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"reason\":\"sized up on purpose\"}").exchange().expectStatus().isOk());
        assertThat(accepted.path("resolution").asText()).isEqualTo("accepted");
        assertThat(accepted.path("reason").asText()).isEqualTo("sized up on purpose");

        // the desired cpu is 8 now, and the next reads find nothing to report
        await(() -> vm("web-01"), w -> w.path("desired").path("cpu").asInt() == 8 && w.hasNonNull("lastCheckedAt")
                && w.path("lastCheckedAt").asText().compareTo(accepted.path("closedAt").asText()) > 0, "a read after the accept");
        JsonNode open = body(dana().get().uri(uri("/v1/findings?wfrn=" + encode(wfrn))).exchange().expectStatus().isOk());
        assertThat(open.path("items")).isEmpty();
    }

    @Test
    void ignoreSilencesTheFieldsUntilTheDesiredStateChanges() {
        JsonNode vm = vm("shop-01");
        String wfrn = vm.path("wfrn").asText();
        setAtProvider(vm.path("providerId").asText(), "memoryGb", 16);
        JsonNode finding = openFinding(wfrn);
        JsonNode ignored = body(dana().post().uri("/v1/findings/" + finding.path("findingId").asText() + ":ignore")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"reason\":\"load test\",\"until\":\"2099-01-01T00:00:00Z\"}")
                .exchange().expectStatus().isOk());
        assertThat(ignored.path("resolution").asText()).isEqualTo("ignored");
        assertThat(ignored.path("ignoredUntil").asText()).isEqualTo("2099-01-01T00:00:00Z");

        JsonNode silenced = await(() -> vm("shop-01"), w -> w.path("ignored").size() == 1 && w.hasNonNull("lastCheckedAt")
                && w.path("lastCheckedAt").asText().compareTo(ignored.path("closedAt").asText()) > 0, "a read after the ignore");
        assertThat(silenced.path("ignored").get(0).path("path").asText()).isEqualTo("/memoryGb");
        JsonNode open = body(dana().get().uri(uri("/v1/findings?wfrn=" + encode(wfrn))).exchange().expectStatus().isOk());
        assertThat(open.path("items")).isEmpty();
        // a revert of an ignored finding is refused: it is closed
        dana().post().uri("/v1/findings/" + finding.path("findingId").asText() + ":revert").exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void discoveryListsWhatTheConsoleDoesNotManageAndImportTakesItUnder() {
        JsonNode run = body(dana().post().uri("/v1/discover").contentType(MediaType.APPLICATION_JSON).bodyValue("{\"type\":\"vm\",\"region\":\"global\"}")
                .exchange().expectStatus().isAccepted());
        ContractSchemas.assertValid(ContractSchemas.DISCOVERY_RUN, run, "the run");
        String runId = run.path("runId").asText();
        JsonNode done = await(() -> body(dana().get().uri("/v1/discover/" + runId).exchange().expectStatus().isOk()),
                r -> "succeeded".equals(r.path("status").asText()), "the discovery run");
        ContractSchemas.assertValid(ContractSchemas.DISCOVERY_RUN, done, "the finished run");
        assertThat(done.path("unmanaged").asInt()).isPositive();

        JsonNode unmanaged = body(dana().get().uri("/v1/unmanaged?type=vm").exchange().expectStatus().isOk());
        List<String> ids = new ArrayList<>();
        unmanaged.path("items").forEach(o -> {
            ContractSchemas.assertValid(ContractSchemas.UNMANAGED, o, "an unmanaged object");
            ids.add(o.path("providerId").asText());
        });
        assertThat(ids).contains("vm-2001");

        JsonNode imported = body(dana().post().uri("/v1/unmanaged:import").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"type\":\"vm\",\"region\":\"global\",\"providerId\":\"vm-2001\",\"name\":\"legacy-01\"}").exchange().expectStatus().isCreated());
        String wfrn = imported.path("wfrn").asText();
        assertThat(wfrn).startsWith("wfrn:wf:compute:global:" + ACCOUNT + ":vm/");

        JsonNode after = body(dana().get().uri("/v1/unmanaged?type=vm").exchange().expectStatus().isOk());
        assertThat(after.path("items").findValuesAsText("providerId")).doesNotContain("vm-2001");
        // the imported VM is read from the provider's own pages: a few scans later it is checked and in line
        JsonNode watched = await(() -> body(service().get().uri(uri("/internal/watches/" + encode(wfrn))).exchange().expectStatus().isOk()),
                w -> w.hasNonNull("lastCheckedAt"), "a read of the imported VM");
        assertThat(watched.path("openFindingId").isMissingNode() || watched.path("openFindingId").isNull()).isTrue();
        // a second run does not list it again
        String second = body(dana().post().uri("/v1/discover").contentType(MediaType.APPLICATION_JSON).bodyValue("{\"type\":\"vm\",\"region\":\"global\"}")
                .exchange().expectStatus().isAccepted()).path("runId").asText();
        await(() -> body(dana().get().uri("/v1/discover/" + second).exchange().expectStatus().isOk()), r -> "succeeded".equals(r.path("status").asText()),
                "the second run");
        assertThat(body(dana().get().uri("/v1/unmanaged?type=vm").exchange().expectStatus().isOk()).path("items").findValuesAsText("providerId"))
                .doesNotContain("vm-2001");
    }

    @Test
    void aSignedWebhookMakesTheNamedResourceDue() {
        JsonNode vm = vm("web-02");
        byte[] payload = ("{\"event\":\"vm.changed\",\"objects\":[{\"providerId\":\"" + vm.path("providerId").asText() + "\",\"type\":\"vm\"}]}")
                .getBytes(StandardCharsets.UTF_8);
        JsonNode scheduled = body(anonymous().post().uri("/v1/webhooks/vsphere").contentType(MediaType.APPLICATION_JSON)
                .header("X-Webhook-Signature", "sha256=" + WebhookService.sign("demo-vsphere-webhook-secret", payload)).bodyValue(payload)
                .exchange().expectStatus().isAccepted());
        assertThat(scheduled.path("scheduled").asInt()).isEqualTo(1);
        anonymous().post().uri("/v1/webhooks/vsphere").contentType(MediaType.APPLICATION_JSON).header("X-Webhook-Signature", "sha256=00")
                .bodyValue(payload).exchange().expectStatus().isUnauthorized();
        anonymous().post().uri("/v1/webhooks/nobody").contentType(MediaType.APPLICATION_JSON).header("X-Webhook-Signature", "sha256=00")
                .bodyValue(payload).exchange().expectStatus().isUnauthorized();
    }

    @Test
    void anotherAccountSeesNothingAndARevertAlwaysNeedsAPerson() {
        JsonNode vm = vm("batch-01");
        String wfrn = vm.path("wfrn").asText();
        setAtProvider(vm.path("providerId").asText(), "cpu", 4);
        String findingId = openFinding(wfrn).path("findingId").asText();

        as(OTHER_ACCOUNT, "ana", "team-data").get().uri("/v1/findings/" + findingId).exchange().expectStatus().isNotFound();
        as(OTHER_ACCOUNT, "ana", "team-data").post().uri("/v1/findings/" + findingId + ":revert").exchange().expectStatus().isNotFound();
        JsonNode refused = body(service().post().uri("/v1/findings/" + findingId + ":revert").exchange().expectStatus().isEqualTo(409));
        ContractSchemas.assertValid(ContractSchemas.ERROR, refused, "the refusal");
        assertThat(refused.path("error").asText()).startsWith("revert.needsPerson");
        anonymous().get().uri("/v1/findings").exchange().expectStatus().isUnauthorized();
    }
}
