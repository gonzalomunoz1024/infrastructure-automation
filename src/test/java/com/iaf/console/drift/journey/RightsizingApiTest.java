package com.iaf.console.drift.journey;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.fake.runs.domain.Job;
import com.networknt.schema.JsonSchema;

/**
 * The rightsizing API in one story: the utilisation pass an operator runs opens a recommendation
 * for each idle and oversized demo VM, priced and listed largest saving first; the summary adds
 * them up; one is dismissed until a date, one resized and one deleted on behalf of the person who
 * asks, each decision answered once under its Idempotency-Key, and a closed one refused.
 */
class RightsizingApiTest extends MonitoringServiceTest {

    static final JsonSchema RECOMMENDATION = schema("Recommendation");
    static final JsonSchema SUMMARY = schema("RecommendationsSummary");
    static final JsonSchema UTILISATION_REPORT = schema("UtilisationReport");

    @Test
    void thePassRecommendsAndAPersonDismissesResizesAndDeletes() {
        String web02 = vm("web-02").path("wfrn").asText();
        String batch01 = vm("batch-01").path("wfrn").asText();
        String shop01 = vm("shop-01").path("wfrn").asText();
        vm("web-01");
        vm("api-01");
        vm("cache-01");

        // the pass: only for the console's services, and only for an account id
        dana().post().uri("/internal/utilisation").exchange().expectStatus().isNotFound();
        service().post().uri("/internal/utilisation?accountId=123").exchange().expectStatus().isBadRequest();
        JsonNode report = body(service().post().uri("/internal/utilisation?accountId=" + ACCOUNT).exchange().expectStatus().isOk());
        ContractSchemas.assertValid(UTILISATION_REPORT, report, "the report");
        assertThat(report.path("opened").asInt()).isEqualTo(3);
        assertThat(report.path("read").asInt()).isGreaterThanOrEqualTo(5);
        assertThat(report.path("superseded").asInt()).isZero();
        assertThat(report.path("errors").asInt()).isZero();

        // the largest saving first: the idle small VMs save all of their 60, the oversized medium one 100 - 60
        JsonNode page = body(dana().get().uri("/v1/recommendations").exchange().expectStatus().isOk());
        page.path("items").forEach(r -> ContractSchemas.assertValid(RECOMMENDATION, r, "a recommendation"));
        assertThat(wfrns(page)).containsExactlyInAnyOrder(web02, batch01, shop01);
        assertThat(wfrns(page).get(2)).isEqualTo(web02);
        assertThat(page.path("next").isNull()).isTrue();

        JsonNode resize = only(body(dana().get().uri(uri("/v1/recommendations?wfrn=" + encode(web02))).exchange().expectStatus().isOk()));
        assertThat(resize.path("kind").asText()).isEqualTo("oversized");
        assertThat(resize.path("action").asText()).isEqualTo("resize");
        assertThat(resize.path("status").asText()).isEqualTo("open");
        assertThat(resize.path("name").asText()).isEqualTo("web-02");
        assertThat(resize.path("current").path("cpu").asInt()).isEqualTo(4);
        assertThat(resize.path("current").path("memoryGb").asInt()).isEqualTo(16);
        assertThat(resize.path("proposed").path("cpu").asInt()).isEqualTo(2);
        assertThat(resize.path("proposed").path("memoryGb").asInt()).isEqualTo(8);
        assertThat(resize.path("utilisation").path("samples").asInt()).isGreaterThanOrEqualTo(168);
        assertThat(resize.path("utilisation").path("cpuP95").asDouble()).isLessThan(20);
        assertThat(resize.path("utilisation").path("memoryP95").asDouble()).isLessThan(30);
        assertThat(resize.path("monthlyCost").path("amount").decimalValue()).isEqualByComparingTo("100");
        assertThat(resize.path("monthlySaving").path("amount").decimalValue()).isEqualByComparingTo("40");
        assertThat(resize.path("monthlySaving").path("currency").asText()).isEqualTo("USD");
        assertThat(resize.path("monthlySaving").path("basis").asText()).isEqualTo(PRICE_BASIS);

        JsonNode delete = only(body(dana().get().uri(uri("/v1/recommendations?wfrn=" + encode(batch01))).exchange().expectStatus().isOk()));
        assertThat(delete.path("kind").asText()).isEqualTo("idle");
        assertThat(delete.path("action").asText()).isEqualTo("delete");
        assertThat(delete.has("proposed")).isFalse();
        assertThat(delete.path("current").path("cpu").asInt()).isEqualTo(2);
        assertThat(delete.path("utilisation").path("cpuP95").asDouble()).isLessThan(5);
        assertThat(delete.path("monthlySaving").path("amount").decimalValue()).isEqualByComparingTo("60");
        String dismissId = only(body(dana().get().uri(uri("/v1/recommendations?wfrn=" + encode(shop01))).exchange().expectStatus().isOk()))
                .path("recommendationId").asText();

        // filters and pages
        assertThat(body(dana().get().uri("/v1/recommendations?kind=idle").exchange().expectStatus().isOk()).path("items")).hasSize(2);
        assertThat(wfrns(body(dana().get().uri("/v1/recommendations?kind=oversized&type=vm").exchange().expectStatus().isOk()))).containsExactly(web02);
        assertThat(body(dana().get().uri("/v1/recommendations?type=volume").exchange().expectStatus().isOk()).path("items")).isEmpty();
        assertThat(body(dana().get().uri("/v1/recommendations?status=applied").exchange().expectStatus().isOk()).path("items")).isEmpty();
        JsonNode first = body(dana().get().uri("/v1/recommendations?limit=2").exchange().expectStatus().isOk());
        assertThat(first.path("items")).hasSize(2);
        JsonNode rest = body(dana().get().uri(uri("/v1/recommendations?limit=2&after=" + encode(first.path("next").asText()))).exchange()
                .expectStatus().isOk());
        assertThat(wfrns(rest)).containsExactly(web02);
        assertThat(rest.path("next").isNull()).isTrue();
        dana().get().uri("/v1/recommendations?kind=huge").exchange().expectStatus().isBadRequest();
        dana().get().uri("/v1/recommendations?status=done").exchange().expectStatus().isBadRequest();
        dana().get().uri("/v1/recommendations?wfrn=nope").exchange().expectStatus().isBadRequest();
        dana().get().uri("/v1/recommendations?limit=0").exchange().expectStatus().isBadRequest();

        // what they add up to
        JsonNode summary = body(dana().get().uri("/v1/recommendations/summary").exchange().expectStatus().isOk());
        ContractSchemas.assertValid(SUMMARY, summary, "the summary");
        assertThat(summary.path("open").asInt()).isEqualTo(3);
        assertThat(summary.path("byKind").path("idle").asInt()).isEqualTo(2);
        assertThat(summary.path("byKind").path("oversized").asInt()).isEqualTo(1);
        assertThat(summary.path("monthlySaving").path("amount").decimalValue()).isEqualByComparingTo("160");
        assertThat(summary.hasNonNull("lastPassAt")).isTrue();

        // one recommendation, never another account's
        String resizeId = resize.path("recommendationId").asText();
        ContractSchemas.assertValid(RECOMMENDATION, body(dana().get().uri("/v1/recommendations/" + resizeId).exchange().expectStatus().isOk()),
                "the recommendation");
        assertThat(body(service().get().uri("/v1/recommendations/" + resizeId).exchange().expectStatus().isOk()).path("wfrn").asText()).isEqualTo(web02);
        dana().get().uri("/v1/recommendations/rec-none").exchange().expectStatus().isNotFound();
        WebTestClient omar = as(OTHER_ACCOUNT, "omar", "team-data,developer");
        omar.get().uri("/v1/recommendations/" + resizeId).exchange().expectStatus().isNotFound();
        omar.post().uri("/v1/recommendations/" + resizeId + ":apply").exchange().expectStatus().isNotFound();
        assertThat(body(omar.get().uri("/v1/recommendations").exchange().expectStatus().isOk()).path("items")).isEmpty();
        assertThat(body(omar.get().uri("/v1/recommendations/summary").exchange().expectStatus().isOk()).path("open").asInt()).isZero();

        // dismissed until a date: a date gone by is a 400; the decision is answered once under its key
        assertError(body(dismiss(dana(), dismissId, "{\"until\":\"2020-01-01T00:00:00Z\"}", null).expectStatus().isBadRequest()), "");
        String until = Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString();
        String why = "{\"reason\":\"the shop reopens next month\",\"until\":\"" + until + "\"}";
        JsonNode dismissed = body(dismiss(dana(), dismissId, why, "dismiss-shop-01").expectStatus().isOk()
                .expectHeader().doesNotExist("Idempotent-Replay"));
        ContractSchemas.assertValid(RECOMMENDATION, dismissed, "the dismissed recommendation");
        assertThat(dismissed.path("status").asText()).isEqualTo("dismissed");
        assertThat(dismissed.path("decidedBy").asText()).isEqualTo("dana");
        assertThat(dismissed.path("reason").asText()).isEqualTo("the shop reopens next month");
        assertThat(Instant.parse(dismissed.path("dismissedUntil").asText())).isEqualTo(Instant.parse(until));
        JsonNode replayed = body(dismiss(dana(), dismissId, why, "dismiss-shop-01").expectStatus().isOk()
                .expectHeader().valueEquals("Idempotent-Replay", "true"));
        assertThat(replayed).isEqualTo(dismissed);
        assertError(body(dismiss(dana(), dismissId, why, null).expectStatus().isEqualTo(409)), "recommendation.closed");

        // applying runs for a person: a console service without the BFF's assertion is refused
        String deleteId = delete.path("recommendationId").asText();
        assertError(body(service().post().uri("/v1/recommendations/" + deleteId + ":apply").exchange().expectStatus().isEqualTo(409)),
                "revert.needsPerson");

        // dana resizes web-02 (a developer may update a VM): 202 once the job is started, applied at once
        JsonNode applied = body(apply(dana(), resizeId, "{\"reason\":\"two weeks under 15% CPU\"}", "apply-web-02").expectStatus().isAccepted()
                .expectHeader().doesNotExist("Idempotent-Replay"));
        assertThat(applied.path("recommendationId").asText()).isEqualTo(resizeId);
        String jobId = applied.path("jobId").asText();
        assertThat(jobId).isNotBlank();
        JsonNode after = body(dana().get().uri("/v1/recommendations/" + resizeId).exchange().expectStatus().isOk());
        ContractSchemas.assertValid(RECOMMENDATION, after, "the applied recommendation");
        assertThat(after.path("status").asText()).isEqualTo("applied");
        assertThat(after.path("jobId").asText()).isEqualTo(jobId);
        assertThat(after.path("decidedBy").asText()).isEqualTo("dana");
        assertThat(after.path("reason").asText()).isEqualTo("two weeks under 15% CPU");
        JsonNode again = body(apply(dana(), resizeId, "{\"reason\":\"two weeks under 15% CPU\"}", "apply-web-02").expectStatus().isAccepted()
                .expectHeader().valueEquals("Idempotent-Replay", "true"));
        assertThat(again.path("jobId").asText()).isEqualTo(jobId);
        assertError(body(apply(dana(), resizeId, null, null).expectStatus().isEqualTo(409)), "recommendation.closed");
        Job job = fakeJob(jobId);
        assertThat(job.workflow()).isEqualTo("update-vm");
        assertThat(job.input().path("cpu").asInt()).isEqualTo(2);
        assertThat(job.input().path("memoryGb").asInt()).isEqualTo(8);
        assertThat(job.onBehalfOf().toString()).contains("dana");

        // olga of ops deletes the idle batch-01 (deleting a VM is for ops)
        JsonNode deleted = body(apply(as(ACCOUNT, "olga", "team-compute,ops"), deleteId, "{\"reason\":\"idle for two weeks\"}", null)
                .expectStatus().isAccepted());
        Job deleting = fakeJob(deleted.path("jobId").asText());
        assertThat(deleting.workflow()).isEqualTo("delete-vm");
        assertThat(deleting.input().path("reason").asText()).isEqualTo("idle for two weeks");
        JsonNode gone = body(dana().get().uri("/v1/recommendations/" + deleteId).exchange().expectStatus().isOk());
        assertThat(gone.path("status").asText()).isEqualTo("applied");
        assertThat(gone.path("decidedBy").asText()).isEqualTo("olga");

        // nothing is open now, and a second pass leaves the changed VMs and the dismissal alone
        JsonNode none = body(dana().get().uri("/v1/recommendations/summary").exchange().expectStatus().isOk());
        assertThat(none.path("open").asInt()).isZero();
        assertThat(none.path("byKind").path("idle").asInt()).isZero();
        assertThat(none.path("monthlySaving").path("amount").decimalValue()).isEqualByComparingTo("0");
        assertThat(body(dana().get().uri("/v1/recommendations?status=applied").exchange().expectStatus().isOk()).path("items")).hasSize(2);
        JsonNode second = body(service().post().uri("/internal/utilisation?accountId=" + ACCOUNT).exchange().expectStatus().isOk());
        assertThat(second.path("opened").asInt()).isZero();
        assertThat(second.path("superseded").asInt()).isZero();
        assertThat(body(dana().get().uri("/v1/recommendations?status=dismissed").exchange().expectStatus().isOk()).path("items")).hasSize(1);
    }

    private WebTestClient.ResponseSpec apply(WebTestClient client, String id, String body, String key) {
        WebTestClient.RequestBodySpec post = client.post().uri("/v1/recommendations/" + id + ":apply");
        if (key != null) {
            post = post.header("Idempotency-Key", key);
        }
        return body == null ? post.exchange() : post.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    private WebTestClient.ResponseSpec dismiss(WebTestClient client, String id, String body, String key) {
        WebTestClient.RequestBodySpec post = client.post().uri("/v1/recommendations/" + id + ":dismiss");
        if (key != null) {
            post = post.header("Idempotency-Key", key);
        }
        return post.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    /** A job as the fake platform holds it. */
    private Job fakeJob(String jobId) {
        return fake.jobs().get(jobId).orElseThrow();
    }

    private static JsonNode only(JsonNode page) {
        assertThat(page.path("items")).hasSize(1);
        return page.path("items").get(0);
    }

    private static List<String> wfrns(JsonNode page) {
        List<String> wfrns = new ArrayList<>();
        page.path("items").forEach(r -> wfrns.add(r.path("wfrn").asText()));
        return wfrns;
    }
}
