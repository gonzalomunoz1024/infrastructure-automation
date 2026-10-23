package com.iaf.console.drift.journey;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.drift.drift.domain.Json;
import com.iaf.console.drift.scorecards.application.ScorecardEvents;
import com.iaf.console.kit.outbox.InMemoryOutbox;
import com.iaf.console.kit.outbox.OutboxEntry;
import com.iaf.console.kit.outbox.OutboxPort;

/**
 * Compliance scorecards on the demo (WP 9.6): the demo's resources are scored as the sync and the
 * scans hear them; an account's scorecard, a rule's failing resources, one resource's results, a
 * one-click tag fix in the person's name and the re-score it brings, the heat map for the
 * console's own services only, and the events, each in the pinned contract's shapes.
 */
class ScorecardJourneyTest extends DemoServiceTest {

    @Autowired
    OutboxPort outbox;

    private JsonNode scorecard() {
        return await(() -> body(dana().get().uri("/v1/scorecards/account?days=7").exchange().expectStatus().isOk()),
                s -> s.path("resources").asLong() > 0, "the compute account's resources to be scored");
    }

    /** A required-tags rule some resource of the account fails, by the scorecard. */
    private static JsonNode failingTagRule(JsonNode card) {
        for (JsonNode pack : card.path("packs")) {
            if (!"required-tags".equals(pack.path("name").asText())) {
                continue;
            }
            for (JsonNode rule : pack.path("rules")) {
                if (rule.path("failed").asLong() > 0 && rule.path("remediable").asBoolean()) {
                    return rule;
                }
            }
        }
        return null;
    }

    @Test
    void theAccountIsScoredAndATagFixInThePersonsNameScoresItAgain() {
        JsonNode packs = body(dana().get().uri("/v1/scorecards/packs").exchange().expectStatus().isOk());
        assertThat(packs.path("items")).hasSize(3);
        for (JsonNode p : packs.path("items")) {
            ContractSchemas.assertValid(ContractSchemas.RULE_PACK, p, "a pack");
        }

        JsonNode card = scorecard();
        ContractSchemas.assertValid(ContractSchemas.SCORECARD, card, "the scorecard");
        assertThat(card.path("accountId").asText()).isEqualTo(ACCOUNT);
        assertThat(card.path("packs")).hasSize(3);
        assertThat(card.path("trend")).isNotEmpty();
        JsonNode rule = await(() -> failingTagRule(scorecard()), r -> true, "a required tag the demo's resources lack");
        String ruleId = rule.path("id").asText();

        JsonNode failing = body(dana().get().uri("/v1/scorecards/packs/required-tags/rules/" + ruleId + "/failing?limit=5").exchange().expectStatus().isOk());
        assertThat(failing.path("items")).isNotEmpty();
        for (JsonNode e : failing.path("items")) {
            ContractSchemas.assertValid(ContractSchemas.EVALUATION, e, "a failing resource");
            assertThat(e.path("status").asText()).isEqualTo("fail");
        }
        String wfrn = failing.path("items").get(0).path("wfrn").asText();

        JsonNode compliance = body(dana().get().uri(uri("/v1/scorecards/resources?wfrn=" + encode(wfrn))).exchange().expectStatus().isOk());
        ContractSchemas.assertValid(ContractSchemas.RESOURCE_COMPLIANCE, compliance, "the resource's results");
        assertThat(compliance.path("failed").asLong()).isPositive();

        // the fix: tags set in Dana's name, answered once per Idempotency-Key
        String input = switch (ruleId) {
            case "owner" -> "{\"owner\":\"team-compute\"}";
            case "cost-centre" -> "{\"costCentre\":\"CC-1234\"}";
            case "data-class" -> "{\"dataClass\":\"internal\"}";
            default -> "{\"environment\":\"dev\"}";
        };
        String request = "{\"wfrn\":\"" + wfrn + "\",\"input\":" + input + ",\"reason\":\"tag it\"}";
        String path = "/v1/scorecards/packs/required-tags/rules/" + ruleId + ":remediate";
        JsonNode fixed = body(dana().post().uri(path).contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", "fix-" + ruleId)
                .bodyValue(request).exchange().expectStatus().isOk().expectHeader().doesNotExist("Idempotent-Replay"));
        ContractSchemas.assertValid(ContractSchemas.REMEDIATION, fixed, "the remediation");
        assertThat(fixed.path("kind").asText()).isEqualTo("tags");
        assertThat(fixed.path("tags").size()).isEqualTo(1);
        JsonNode replay = body(dana().post().uri(path).contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", "fix-" + ruleId)
                .bodyValue(request).exchange().expectStatus().isOk().expectHeader().valueEquals("Idempotent-Replay", "true"));
        assertThat(replay).isEqualTo(fixed);

        // the sync hears the change and the rule passes
        await(() -> body(dana().get().uri(uri("/v1/scorecards/resources?wfrn=" + encode(wfrn))).exchange().expectStatus().isOk()), c -> {
            for (JsonNode r : c.path("results")) {
                if ("required-tags".equals(r.path("pack").asText()) && ruleId.equals(r.path("ruleId").asText())) {
                    return "pass".equals(r.path("status").asText());
                }
            }
            return false;
        }, "the tagged resource to pass " + ruleId);
        JsonNode again = body(dana().post().uri(path).contentType(MediaType.APPLICATION_JSON).bodyValue(request).exchange().expectStatus().isEqualTo(409));
        assertThat(again.path("error").asText()).startsWith("evaluation.passing");

        List<JsonNode> events = events(wfrn);
        assertThat(events).extracting(e -> e.path("type").asText()).contains(ScorecardEvents.EVALUATION_CHANGED);
        for (JsonNode e : events) {
            ContractSchemas.assertValid(ContractSchemas.EVALUATION_CHANGED, e, "an EvaluationChanged");
        }
    }

    @Test
    void aFixNeedsAPersonAndAValidInput() {
        JsonNode rule = await(() -> failingTagRule(scorecard()), r -> true, "a required tag the demo's resources lack");
        String ruleId = rule.path("id").asText();
        String wfrn = body(dana().get().uri("/v1/scorecards/packs/required-tags/rules/" + ruleId + "/failing?limit=1").exchange().expectStatus().isOk())
                .path("items").get(0).path("wfrn").asText();
        String path = "/v1/scorecards/packs/required-tags/rules/" + ruleId + ":remediate";

        // a service that relays no person
        JsonNode noPerson = body(service().post().uri(path + "?accountId=" + ACCOUNT).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"wfrn\":\"" + wfrn + "\",\"input\":{\"owner\":\"x\",\"costCentre\":\"CC-1234\",\"dataClass\":\"internal\",\"environment\":\"dev\"}}")
                .exchange().expectStatus().isEqualTo(409));
        assertThat(noPerson.path("error").asText()).startsWith("remediation.needsPerson");

        // the data-class fix takes one of its enum
        dana().post().uri("/v1/scorecards/packs/required-tags/rules/data-class:remediate").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"wfrn\":\"" + wfrn + "\",\"input\":{\"dataClass\":\"top-secret\"}}").exchange().expectStatus().isBadRequest();
        dana().post().uri(path).contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange().expectStatus().isBadRequest();
        dana().post().uri("/v1/scorecards/packs/required-tags/rules/no-such-rule:remediate").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"wfrn\":\"" + wfrn + "\"}").exchange().expectStatus().isNotFound();
    }

    @Test
    void theHeatMapAndTheOperationsAreForTheConsolesOwnServices() {
        scorecard();
        JsonNode heatmap = body(service().get().uri("/v1/scorecards/heatmap?accountIds=" + ACCOUNT + "," + OTHER_ACCOUNT).exchange().expectStatus().isOk());
        ContractSchemas.assertValid(ContractSchemas.HEATMAP, heatmap, "the heat map");
        assertThat(heatmap.path("rows")).extracting(r -> r.path("accountId").asText()).containsExactly(ACCOUNT, OTHER_ACCOUNT);
        assertThat(heatmap.path("packs")).hasSize(3);
        service().get().uri("/v1/scorecards/heatmap?accountIds=12345").exchange().expectStatus().isBadRequest();
        dana().get().uri("/v1/scorecards/heatmap?accountIds=" + ACCOUNT).exchange().expectStatus().isNotFound();

        // another account's scorecard is not there for Dana
        dana().get().uri("/v1/scorecards/account?accountId=" + OTHER_ACCOUNT).exchange().expectStatus().isNotFound();
        dana().get().uri("/v1/scorecards/account?days=0").exchange().expectStatus().isBadRequest();
        dana().get().uri("/v1/scorecards/packs/required-tags/rules/owner/failing?after=not-a-cursor").exchange().expectStatus().isBadRequest();
        dana().get().uri("/v1/scorecards/packs/nope/rules/owner/failing").exchange().expectStatus().isNotFound();
        dana().get().uri("/v1/scorecards/resources?wfrn=not-a-wfrn").exchange().expectStatus().isBadRequest();

        JsonNode rescored = body(service().post().uri("/internal/scorecards:rescore?accountId=" + ACCOUNT).exchange().expectStatus().isOk());
        assertThat(rescored.path("resources").asInt()).isPositive();
        JsonNode snapshot = body(service().post().uri("/internal/scorecards:snapshot").exchange().expectStatus().isOk());
        assertThat(snapshot.path("accounts").asInt()).isPositive();
        dana().post().uri("/internal/scorecards:snapshot").exchange().expectStatus().isNotFound();
        dana().post().uri("/internal/scorecards:rescore").exchange().expectStatus().isNotFound();

        boolean any = false;
        for (OutboxEntry e : ((InMemoryOutbox) outbox).all().collectList().block()) {
            if (ScorecardEvents.TOPIC.equals(e.topic()) && ScorecardEvents.SCORE_SNAPSHOTTED.equals(e.event().type())) {
                ContractSchemas.assertValid(ContractSchemas.SCORE_SNAPSHOTTED, Json.MAPPER.valueToTree(e.event()), "a ScoreSnapshotted");
                any = true;
            }
        }
        assertThat(any).isTrue();
    }

    private List<JsonNode> events(String wfrn) {
        return ((InMemoryOutbox) outbox).all().collectList().block().stream()
                .filter(e -> ScorecardEvents.TOPIC.equals(e.topic()) && wfrn.equals(e.event().aggregateId()))
                .<JsonNode>map(e -> Json.MAPPER.valueToTree(e.event()))
                .toList();
    }
}
