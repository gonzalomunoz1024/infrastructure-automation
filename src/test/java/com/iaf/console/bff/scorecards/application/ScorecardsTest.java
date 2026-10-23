package com.iaf.console.bff.scorecards.application;

import static com.iaf.console.bff.cost.CostFixtures.COMPUTE;
import static com.iaf.console.bff.cost.CostFixtures.DATA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.function.BiPredicate;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.bff.access.domain.DeniedException;
import com.iaf.console.bff.common.relay.Relayed;
import com.iaf.console.bff.cost.CostFixtures;
import com.iaf.console.bff.cost.CostFixtures.Answer;
import com.iaf.console.bff.cost.CostFixtures.Stub;
import com.iaf.console.bff.platform.application.PlatformProperties;
import com.iaf.console.bff.scorecards.adapters.outbound.http.HttpScorecardsPort;

/**
 * The scorecards against a stub drift service: the heat map reads only the accounts the person may
 * see, with the service token, and names each row's account; a one-click fix needs
 * {@code scorecards:remediate} and the fix's own action, relays the person's assertion, and the
 * drift service's refusals come back as they were.
 */
class ScorecardsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String VM = "wfrn:wf:compute:global:" + COMPUTE + ":vm/web-01";
    private static final String PACKS = "{\"items\":[{\"name\":\"baseline\",\"title\":\"Baseline\",\"version\":1,\"rules\":["
            + "{\"id\":\"owner-tag\",\"title\":\"Owner\",\"severity\":\"warning\",\"types\":[\"vm\"],\"remediation\":{\"title\":\"Set it\",\"kind\":\"tags\",\"inputs\":[]}},"
            + "{\"id\":\"restart-weekly\",\"title\":\"Restarted\",\"severity\":\"info\",\"types\":[\"vm\"],\"remediation\":{\"title\":\"Restart\",\"kind\":\"workflow\",\"verb\":\"restart\",\"workflow\":\"vm-restart\",\"inputs\":[]}}]}]}";

    private static Scorecards scorecards(String url, BiPredicate<String, String> allows) {
        HttpScorecardsPort port = new HttpScorecardsPort(CostFixtures.client(url, "console-drift-service", Duration.ofSeconds(2)));
        PlatformProperties platform = new PlatformProperties("http://platform.test", "", "wf", Duration.ofSeconds(5), Duration.ofMinutes(10), null, null);
        return new Scorecards(CostFixtures.access(allows), CostFixtures.sessions(), CostFixtures.trust(), platform, port, JSON);
    }

    private static Stub drift(Answer remediation) {
        return new Stub(seen -> {
            if (seen.path().endsWith("/packs")) {
                return Answer.json(200, PACKS);
            }
            if (seen.path().endsWith("/heatmap")) {
                return Answer.json(200, "{\"packs\":[{\"name\":\"baseline\",\"title\":\"Baseline\"}],\"rows\":[{\"accountId\":\"" + COMPUTE
                        + "\",\"score\":80,\"failed\":2,\"packs\":{\"baseline\":80}}]}");
            }
            return remediation;
        });
    }

    @Test
    void theHeatMapReadsOnlyTheAccountsThePersonMaySeeAndNamesThem() throws Exception {
        try (Stub drift = drift(Answer.json(500, "{}"))) {
            Relayed answer = scorecards(drift.url(), (account, action) -> COMPUTE.equals(account)).heatmap(CostFixtures.session()).block();
            assertEquals(200, answer.status());
            JsonNode body = JSON.readTree(answer.body());
            assertEquals("Compute", body.path("rows").get(0).path("accountName").asText(), body.toString());
            CostFixtures.Seen call = drift.seen.get(0);
            assertEquals("accountIds=" + COMPUTE, call.rawQuery(), "the data account is refused, so never asked for");
            assertFalse(claims(call).has("acct"), "the heat map goes with the service token");

            Relayed none = scorecards(drift.url(), (account, action) -> false).heatmap(CostFixtures.session()).block();
            assertEquals("{\"packs\":[],\"rows\":[]}", none.text(), "no account to show: an empty map, never another account");
            assertEquals(1, drift.seen.size());
        }
    }

    @Test
    void aFixNeedsRemediateAndTheFixsOwnActionAndRelaysThePerson() throws Exception {
        try (Stub drift = drift(new Answer(200, "application/json", "{\"wfrn\":\"" + VM + "\",\"kind\":\"tags\",\"tags\":{\"owner\":\"team-compute\"}}",
                Duration.ZERO))) {
            byte[] body = ("{\"wfrn\":\"" + VM + "\",\"input\":{\"owner\":\"team-compute\"},\"reason\":\"tidy\"}").getBytes(StandardCharsets.UTF_8);

            DeniedException noTag = assertThrows(DeniedException.class, () -> scorecards(drift.url(), (account, action) -> !"compute:tag".equals(action))
                    .remediate(CostFixtures.session(), "baseline", "owner-tag", body, "key-1", "req_1").block());
            assertEquals("compute:tag", noTag.denial().action(), "a tags fix asks <service>:tag");
            DeniedException noRestart = assertThrows(DeniedException.class, () -> scorecards(drift.url(), (account, action) -> !"compute:restart".equals(action))
                    .remediate(CostFixtures.session(), "baseline", "restart-weekly", body, null, "req_2").block());
            assertEquals("compute:restart", noRestart.denial().action(), "a workflow fix asks <service>:<verb>");
            DeniedException noRemediate = assertThrows(DeniedException.class, () -> scorecards(drift.url(), (account, action) -> !"scorecards:remediate".equals(action))
                    .remediate(CostFixtures.session(), "baseline", "owner-tag", body, null, "req_3").block());
            assertEquals(VM, noRemediate.denial().resource());
            assertTrue(drift.seen.stream().noneMatch(s -> s.path().endsWith(":remediate")), "nothing is asked of the drift service once refused");

            Relayed done = scorecards(drift.url(), (account, action) -> true).remediate(CostFixtures.session(), "baseline", "owner-tag", body, "key-1", "req_4").block();
            assertEquals(200, done.status());
            CostFixtures.Seen call = drift.seen.get(drift.seen.size() - 1);
            assertEquals("/v1/scorecards/packs/baseline/rules/owner-tag:remediate", call.path());
            assertNotNull(call.headers().get("x-on-behalf-of"), "the person's assertion is relayed");
            assertEquals("key-1", call.headers().get("idempotency-key"));
            assertEquals("req_4", call.headers().get("x-console-request-id"));
            assertEquals(COMPUTE, claims(call).path("acct").asText(), "as the person");
            assertEquals("tidy", JSON.readTree(call.body()).path("reason").asText());
        }
    }

    @Test
    void theDriftServicesRefusalsComeBackAsTheyWere() {
        try (Stub drift = drift(Answer.json(409, "{\"error\":\"evaluation.passing: the resource passes\"}"))) {
            byte[] body = ("{\"wfrn\":\"" + VM + "\"}").getBytes(StandardCharsets.UTF_8);
            Relayed refused = scorecards(drift.url(), (account, action) -> true).remediate(CostFixtures.session(), "baseline", "owner-tag", body, null, "req").block();
            assertEquals(409, refused.status());
            assertTrue(refused.text().contains("evaluation.passing"));
            assertThrows(com.iaf.console.kit.errors.NotFoundException.class, () -> scorecards(drift.url(), (a, b) -> true)
                    .remediate(CostFixtures.session(), "baseline", "owner-tag", ("{\"wfrn\":\"wfrn:wf:compute:global:" + DATA + ":vm/x\"}")
                            .getBytes(StandardCharsets.UTF_8), null, "req").block(), "another account's resource");
            assertThrows(com.iaf.console.kit.errors.InvalidInputException.class, () -> scorecards(drift.url(), (a, b) -> true)
                    .remediate(CostFixtures.session(), "baseline", "owner-tag", "{}".getBytes(StandardCharsets.UTF_8), null, "req").block());
            assertThrows(com.iaf.console.kit.errors.NotFoundException.class, () -> scorecards(drift.url(), (a, b) -> true)
                    .failing(CostFixtures.session(), "../etc", "x", null, null, null).block());
        }
    }

    private static JsonNode claims(CostFixtures.Seen call) throws Exception {
        String jwt = call.headers().get("authorization").substring("Bearer ".length());
        return JSON.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
    }
}
