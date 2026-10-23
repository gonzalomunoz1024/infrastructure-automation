package com.iaf.console.drift.journey;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The shadow-infrastructure finder (WP 5.3) and the drift numbers (WP 9.5) on the demo: who most
 * likely owns what vSphere has that no account manages, what it costs, handing it to another team,
 * that team taking it under management, and the counts of a window.
 */
class FinderJourneyTest extends DemoServiceTest {

    /** Ana, a developer of the data account. */
    private WebTestClient ana() {
        return as(OTHER_ACCOUNT, "ana", "team-data,developer");
    }

    private static void discover(WebTestClient who) {
        String runId = body(who.post().uri("/v1/discover").contentType(MediaType.APPLICATION_JSON).bodyValue("{\"type\":\"vm\",\"region\":\"global\"}")
                .exchange().expectStatus().isAccepted()).path("runId").asText();
        await(() -> body(who.get().uri("/v1/discover/" + runId).exchange().expectStatus().isOk()), r -> "succeeded".equals(r.path("status").asText()),
                "the discovery run");
    }

    private static JsonNode unmanaged(WebTestClient who, String query) {
        return body(who.get().uri("/v1/unmanaged?type=vm" + query).exchange().expectStatus().isOk());
    }

    private static JsonNode item(JsonNode page, String providerId) {
        for (JsonNode o : page.path("items")) {
            if (providerId.equals(o.path("providerId").asText())) {
                return o;
            }
        }
        return null;
    }

    @Test
    void theFinderGuessesTheOwnerPricesTheObjectAndHandsItToAnotherTeam() {
        discover(dana());

        // every VM the demo manages is the compute account's, so that is the guess for the ones nobody manages
        JsonNode web = item(unmanaged(dana(), "&owner=mine"), "vm-1042");
        assertThat(web).as("vm-1042 among the objects guessed to be the compute account's").isNotNull();
        ContractSchemas.assertValid(ContractSchemas.UNMANAGED, web, "an unmanaged object with its guess and cost");
        JsonNode guess = web.path("ownerGuess");
        assertThat(guess.path("accountId").asText()).isEqualTo(ACCOUNT);
        assertThat(guess.path("accountName").asText()).isEqualTo("Compute");
        assertThat(guess.path("team").asText()).isEqualTo("team-compute");
        assertThat(guess.path("confidence").asText()).isEqualTo("low");
        assertThat(guess.path("reasons").get(0).asText()).isEqualTo("Compute is the only account with managed resources of this type here");
        // the demo price list: 5.00 a month, 14.60 per CPU and 3.65 per GiB, for 4 CPUs and 16 GiB
        assertThat(web.path("monthlyCost").path("amount").decimalValue()).isEqualByComparingTo("121.80");
        assertThat(web.path("monthlyCost").path("currency").asText()).isEqualTo("USD");
        assertThat(web.path("monthlyCost").path("basis").asText()).isEqualTo("list-prices");
        assertThat(unmanaged(dana(), "&owner=unknown").path("items").findValuesAsText("providerId")).doesNotContain("vm-1042", "vm-1043");
        dana().get().uri("/v1/unmanaged?owner=someone").exchange().expectStatus().isBadRequest();

        // Dana hands vm-1043 to the data team; a retry under the same key answers the same
        String assign = "{\"type\":\"vm\",\"region\":\"global\",\"providerId\":\"vm-1043\",\"accountId\":\"" + OTHER_ACCOUNT
                + "\",\"note\":\"Their ETL box\"}";
        String attempt = "assign-vm-1043";
        JsonNode assigned = body(dana().post().uri("/v1/unmanaged:assign").header("Idempotency-Key", attempt)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(assign).exchange().expectStatus().isOk());
        ContractSchemas.assertValid(ContractSchemas.UNMANAGED, assigned, "the assigned object");
        JsonNode assignment = assigned.path("assignment");
        assertThat(assignment.path("accountId").asText()).isEqualTo(OTHER_ACCOUNT);
        assertThat(assignment.path("accountName").asText()).isEqualTo("Data");
        assertThat(assignment.path("fromAccountId").asText()).isEqualTo(ACCOUNT);
        assertThat(assignment.path("by").asText()).isEqualTo("dana");
        assertThat(assignment.path("note").asText()).isEqualTo("Their ETL box");
        dana().post().uri("/v1/unmanaged:assign").header("Idempotency-Key", attempt).contentType(MediaType.APPLICATION_JSON).bodyValue(assign)
                .exchange().expectStatus().isOk().expectHeader().valueEquals("Idempotent-Replay", "true");

        // the data team lists it as theirs, and the compute account still lists it, both with the assignment
        JsonNode theirs = item(unmanaged(ana(), "&owner=mine"), "vm-1043");
        assertThat(theirs).as("vm-1043 in the data account's list").isNotNull();
        ContractSchemas.assertValid(ContractSchemas.UNMANAGED, theirs, "the handed copy");
        assertThat(theirs.path("assignment").path("fromAccountId").asText()).isEqualTo(ACCOUNT);
        assertThat(theirs.path("monthlyCost").path("amount").decimalValue()).isEqualByComparingTo("63.40");
        assertThat(item(unmanaged(dana(), ""), "vm-1043").path("assignment").path("accountId").asText()).isEqualTo(OTHER_ACCOUNT);

        // what cannot be assigned
        String toSelf = assign.replace(OTHER_ACCOUNT, ACCOUNT);
        JsonNode self = body(dana().post().uri("/v1/unmanaged:assign").contentType(MediaType.APPLICATION_JSON).bodyValue(toSelf).exchange()
                .expectStatus().isBadRequest());
        ContractSchemas.assertValid(ContractSchemas.ERROR, self, "the refusal");
        assertThat(self.path("error").asText()).isEqualTo("unmanaged.unknownAccount");
        assertThat(body(dana().post().uri("/v1/unmanaged:assign").contentType(MediaType.APPLICATION_JSON).bodyValue(assign.replace(OTHER_ACCOUNT, "999999999999"))
                .exchange().expectStatus().isBadRequest()).path("error").asText()).isEqualTo("unmanaged.unknownAccount");
        dana().post().uri("/v1/unmanaged:assign").contentType(MediaType.APPLICATION_JSON).bodyValue(assign.replace("vm-1043", "vm-9999")).exchange()
                .expectStatus().isNotFound();
        dana().post().uri("/v1/unmanaged:assign").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(assign.replace("Their ETL box", "x".repeat(501))).exchange().expectStatus().isBadRequest();

        // the data team takes it under management: it leaves both lists, and a later run does not find it again
        JsonNode imported = body(ana().post().uri("/v1/unmanaged:import").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"type\":\"vm\",\"region\":\"global\",\"providerId\":\"vm-1043\"}").exchange().expectStatus().isCreated());
        assertThat(imported.path("wfrn").asText()).startsWith("wfrn:wf:compute:global:" + OTHER_ACCOUNT + ":vm/");
        assertThat(item(unmanaged(ana(), ""), "vm-1043")).isNull();
        assertThat(item(unmanaged(dana(), ""), "vm-1043")).isNull();
        discover(dana());
        assertThat(item(unmanaged(dana(), ""), "vm-1043")).isNull();
    }

    @Test
    void theDriftNumbersCountTheFindingsOpenedAndClosedInTheWindow() {
        // the CPUs of cache-01, a field no other journey changes on it, so their findings still open
        JsonNode vm = vm("cache-01");
        setAtProvider(vm.path("providerId").asText(), "cpu", 6);
        String findingId = openFinding(vm.path("wfrn").asText()).path("findingId").asText();
        dana().post().uri("/v1/findings/" + findingId + ":accept").contentType(MediaType.APPLICATION_JSON).bodyValue("{\"reason\":\"a bigger cache\"}")
                .exchange().expectStatus().isOk();

        JsonNode stats = body(dana().get().uri("/v1/findings/stats").exchange().expectStatus().isOk());
        ContractSchemas.assertValid(ContractSchemas.FINDING_STATS, stats, "the account's numbers");
        assertThat(stats.path("accountId").asText()).isEqualTo(ACCOUNT);
        assertThat(Duration.between(Instant.parse(stats.path("since").asText()), Instant.parse(stats.path("until").asText())))
                .isEqualTo(Duration.ofDays(30));
        assertThat(stats.path("opened").asLong()).isPositive();
        JsonNode closed = stats.path("closed");
        assertThat(closed.path("accepted").asLong()).isPositive();
        assertThat(closed.path("total").asLong())
                .isEqualTo(closed.path("reverted").asLong() + closed.path("accepted").asLong() + closed.path("ignored").asLong() + closed.path("vanished").asLong());
        long bySeverity = 0;
        for (JsonNode n : stats.path("bySeverity")) {
            bySeverity += n.asLong();
        }
        assertThat(bySeverity).isEqualTo(stats.path("opened").asLong());
        assertThat(stats.path("meanMinutesToClose").isNumber()).isTrue();

        // the console's own services read every account at once, or name one
        JsonNode every = body(service().get().uri("/v1/findings/stats").exchange().expectStatus().isOk());
        ContractSchemas.assertValid(ContractSchemas.FINDING_STATS, every, "every account's numbers");
        assertThat(every.has("accountId")).isFalse();
        assertThat(every.path("opened").asLong()).isGreaterThanOrEqualTo(stats.path("opened").asLong());
        assertThat(body(service().get().uri("/v1/findings/stats?accountId=" + OTHER_ACCOUNT).exchange().expectStatus().isOk()).path("accountId").asText())
                .isEqualTo(OTHER_ACCOUNT);

        dana().get().uri(uri("/v1/findings/stats?since=" + encode("2026-10-23T12:00:00Z") + "&until=" + encode("2026-10-23T11:00:00Z"))).exchange()
                .expectStatus().isBadRequest();
        dana().get().uri("/v1/findings/stats?since=yesterday").exchange().expectStatus().isBadRequest();
    }
}
