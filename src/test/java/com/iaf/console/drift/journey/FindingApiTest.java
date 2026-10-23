package com.iaf.console.drift.journey;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.fasterxml.jackson.databind.JsonNode;

/** The findings API's rules: paging, filters, bad input, idempotent decisions, a closed finding, the summary. */
class FindingApiTest extends DemoServiceTest {

    @Test
    void findingsPageAndFilterAndBadInputIsA400() {
        JsonNode a = vm("cache-01");
        JsonNode b = vm("shop-web-1");
        setAtProvider(a.path("providerId").asText(), "memoryGb", 64);
        setAtProvider(b.path("providerId").asText(), "memoryGb", 64);
        openFinding(a.path("wfrn").asText());
        openFinding(b.path("wfrn").asText());

        JsonNode first = body(dana().get().uri("/v1/findings?limit=1&severity=critical").exchange().expectStatus().isOk());
        assertThat(first.path("items")).hasSize(1);
        assertThat(first.path("next").isTextual()).isTrue();
        JsonNode second = body(dana().get().uri(uri("/v1/findings?limit=1&severity=critical&after=" + encode(first.path("next").asText())))
                .exchange().expectStatus().isOk());
        assertThat(second.path("items")).hasSize(1);
        assertThat(second.path("items").get(0).path("findingId").asText()).isNotEqualTo(first.path("items").get(0).path("findingId").asText());

        dana().get().uri("/v1/findings?severity=urgent").exchange().expectStatus().isBadRequest();
        dana().get().uri("/v1/findings?status=pending").exchange().expectStatus().isBadRequest();
        dana().get().uri("/v1/findings?limit=0").exchange().expectStatus().isBadRequest();
        dana().get().uri("/v1/findings?after=not-a-cursor").exchange().expectStatus().isBadRequest();
        dana().get().uri("/v1/findings?wfrn=not-a-wfrn").exchange().expectStatus().isBadRequest();

        JsonNode summary = body(dana().get().uri("/v1/findings/summary").exchange().expectStatus().isOk());
        ContractSchemas.assertValid(ContractSchemas.SUMMARY, summary, "the summary");
        assertThat(summary.path("open").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(summary.path("byType").path("vm").asInt()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void aDecisionWithAnIdempotencyKeyIsAnsweredOnceAndAClosedFindingIsA409() {
        JsonNode vm = vm("shop-web-2");
        setAtProvider(vm.path("providerId").asText(), "cpu", 8);
        String findingId = openFinding(vm.path("wfrn").asText()).path("findingId").asText();

        String body = "{\"reason\":\"keep it\"}";
        JsonNode first = body(dana().post().uri("/v1/findings/" + findingId + ":accept").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "accept-shop-web-2").bodyValue(body).exchange().expectStatus().isOk()
                .expectHeader().doesNotExist("Idempotent-Replay"));
        JsonNode replay = body(dana().post().uri("/v1/findings/" + findingId + ":accept").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "accept-shop-web-2").bodyValue(body).exchange().expectStatus().isOk()
                .expectHeader().valueEquals("Idempotent-Replay", "true"));
        assertThat(replay).isEqualTo(first);

        JsonNode closed = body(dana().post().uri("/v1/findings/" + findingId + ":ignore").contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isEqualTo(409));
        assertThat(closed.path("error").asText()).startsWith("finding.closed");
        dana().get().uri("/v1/findings/fnd-unknown").exchange().expectStatus().isNotFound();
    }
}
