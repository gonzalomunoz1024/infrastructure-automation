package com.iaf.console.drift.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.drift.drift.adapters.loop.InProcessLoops;
import com.iaf.console.drift.drift.domain.Json;
import com.networknt.schema.JsonSchema;

/**
 * The monitoring API: a resource's series with the contract's defaults and bounds, who may read
 * them, and the alarms from creation through evaluation to deletion, firing ones listed first.
 */
class MonitoringApiTest extends MonitoringServiceTest {

    static final JsonSchema METRICS = schema("ResourceMetrics");
    static final JsonSchema ALARM = schema("Alarm");
    static final JsonSchema ALARMS_REPORT = schema("AlarmsReport");

    @Autowired
    InProcessLoops loops;

    @Test
    void theSeriesComeWithTheContractsDefaults() {
        String wfrn = vm("web-01").path("wfrn").asText();

        // no from, to or step: the last 24 hours, a point every five minutes
        JsonNode day = body(dana().get().uri(uri("/v1/metrics?wfrn=" + encode(wfrn))).exchange().expectStatus().isOk());
        ContractSchemas.assertValid(METRICS, day, "the metrics");
        assertThat(day.path("wfrn").asText()).isEqualTo(wfrn);
        Instant from = Instant.parse(day.path("from").asText());
        Instant to = Instant.parse(day.path("to").asText());
        assertThat(to).isCloseTo(Instant.now(), within(2, ChronoUnit.MINUTES));
        assertThat(Duration.between(from, to)).isEqualTo(Duration.ofHours(24));
        assertThat(day.path("step").asText()).isEqualTo("PT5M");
        assertThat(names(day)).containsExactlyInAnyOrder("cpuPercent", "memoryPercent", "diskPercent");
        for (JsonNode series : day.path("series")) {
            assertThat(series.path("points").size()).isBetween(288, 289);
            for (JsonNode point : series.path("points")) {
                assertThat(Instant.parse(point.path("at").asText())).isBetween(from, to);
                assertThat(point.path("value").asDouble()).isBetween(0.0, 100.0);
            }
        }

        // more than a day without a step: a point an hour
        JsonNode week = body(dana().get().uri(uri("/v1/metrics?wfrn=" + encode(wfrn) + "&from=" + encode(Instant.now().minus(Duration.ofDays(3)).toString())))
                .exchange().expectStatus().isOk());
        assertThat(week.path("step").asText()).isEqualTo("PT1H");
        assertThat(week.path("series").get(0).path("points").size()).isBetween(72, 73);

        // all three given, an offset date-time included
        JsonNode exact = body(dana().get().uri(uri("/v1/metrics?wfrn=" + encode(wfrn) + "&from=" + encode("2026-10-20T02:00:00+02:00")
                + "&to=2026-10-20T06:00:00Z&step=PT30M")).exchange().expectStatus().isOk());
        ContractSchemas.assertValid(METRICS, exact, "the metrics");
        assertThat(exact.path("from").asText()).isEqualTo("2026-10-20T00:00:00Z");
        assertThat(exact.path("step").asText()).isEqualTo("PT30M");
        JsonNode points = exact.path("series").get(0).path("points");
        assertThat(points).hasSize(13);
        assertThat(points.get(0).path("at").asText()).isEqualTo("2026-10-20T00:00:00Z");
        assertThat(points.get(12).path("at").asText()).isEqualTo("2026-10-20T06:00:00Z");

        // the same numbers on every read
        JsonNode again = body(dana().get().uri(uri("/v1/metrics?wfrn=" + encode(wfrn) + "&from=" + encode("2026-10-20T02:00:00+02:00")
                + "&to=2026-10-20T06:00:00Z&step=PT30M")).exchange().expectStatus().isOk());
        assertThat(again.path("series")).isEqualTo(exact.path("series"));

        // a console service names no account: the wfrn's
        JsonNode internal = body(service().get().uri(uri("/v1/metrics?wfrn=" + encode(wfrn))).exchange().expectStatus().isOk());
        assertThat(internal.path("wfrn").asText()).isEqualTo(wfrn);
    }

    @Test
    void badInputIsA400AndAResourceOutOfReachIsA404() {
        String wfrn = vm("web-01").path("wfrn").asText();
        String w = "/v1/metrics?wfrn=" + encode(wfrn);
        Instant now = Instant.now();

        dana().get().uri("/v1/metrics").exchange().expectStatus().isBadRequest();
        assertError(body(dana().get().uri("/v1/metrics?wfrn=not-a-wfrn").exchange().expectStatus().isBadRequest()), "");
        assertError(body(dana().get().uri(uri(w + "&step=PT30S")).exchange().expectStatus().isBadRequest()), "");
        dana().get().uri(uri(w + "&step=5m")).exchange().expectStatus().isBadRequest();
        dana().get().uri(uri(w + "&from=yesterday")).exchange().expectStatus().isBadRequest();
        dana().get().uri(uri(w + "&from=" + encode(now.toString()) + "&to=" + encode(now.minus(Duration.ofHours(1)).toString())))
                .exchange().expectStatus().isBadRequest();
        // longer than the 92 days one read may reach, and more steps than it may hold
        dana().get().uri(uri(w + "&from=" + encode(now.minus(Duration.ofDays(100)).toString()))).exchange().expectStatus().isBadRequest();
        dana().get().uri(uri(w + "&from=" + encode(now.minus(Duration.ofDays(30)).toString()) + "&step=PT1M")).exchange().expectStatus().isBadRequest();

        // another account's resource is unknown, and so is one that does not exist
        String other = watchOfType(OTHER_ACCOUNT, "database").path("wfrn").asText();
        assertError(body(dana().get().uri(uri("/v1/metrics?wfrn=" + encode(other))).exchange().expectStatus().isNotFound()), "");
        String missing = wfrn.substring(0, wfrn.lastIndexOf('/') + 1) + "no-such-vm";
        dana().get().uri(uri("/v1/metrics?wfrn=" + encode(missing))).exchange().expectStatus().isNotFound();

        // a type without a metrics read
        String volume = watchOfType(ACCOUNT, "volume").path("wfrn").asText();
        assertError(body(dana().get().uri(uri("/v1/metrics?wfrn=" + encode(volume))).exchange().expectStatus().isNotFound()),
                "metrics.notSupported");

        anonymous().get().uri(uri(w)).exchange().expectStatus().isUnauthorized();
    }

    @Test
    void anAlarmIsCreatedChangedEvaluatedListedAndDeleted() {
        assertThat(loops.names()).contains("alarms", "utilisation");
        String wfrn = vm("web-01").path("wfrn").asText();
        String api01 = vm("api-01").path("wfrn").asText();

        // the average CPU of web-01 is above 0 (it fires); the memory of api-01 is never below 0 (it stays ok)
        JsonNode created = body(put(dana(), "web-01-cpu", alarm(wfrn, "cpuPercent", "above", 0).put("name", "web-01 busy").put("windowMinutes", 15))
                .expectStatus().isCreated());
        ContractSchemas.assertValid(ALARM, created, "the alarm");
        assertThat(created.path("state").asText()).isEqualTo("insufficient");
        assertThat(created.path("createdBy").asText()).isEqualTo("dana");
        assertThat(created.path("type").asText()).isEqualTo("vm");
        assertThat(created.path("accountId").asText()).isEqualTo(ACCOUNT);
        assertThat(created.path("windowMinutes").asInt()).isEqualTo(15);

        // the same id again is a change: 200, the window back to its default, the state kept
        JsonNode changed = body(put(dana(), "web-01-cpu", alarm(wfrn, "cpuPercent", "above", 0.5)).expectStatus().isOk());
        ContractSchemas.assertValid(ALARM, changed, "the changed alarm");
        assertThat(changed.path("threshold").asDouble()).isEqualTo(0.5);
        assertThat(changed.path("windowMinutes").asInt()).isEqualTo(10);
        assertThat(changed.path("state").asText()).isEqualTo("insufficient");
        assertThat(changed.path("createdAt").asText()).isEqualTo(created.path("createdAt").asText());
        assertThat(changed.has("name")).isFalse();

        body(put(dana(), "api-01-memory", alarm(api01, "memoryPercent", "below", 0)).expectStatus().isCreated());

        // the evaluation: only for the console's services
        dana().post().uri("/internal/alarms:evaluate").exchange().expectStatus().isNotFound();
        JsonNode report = body(service().post().uri("/internal/alarms:evaluate").exchange().expectStatus().isOk());
        ContractSchemas.assertValid(ALARMS_REPORT, report, "the report");
        assertThat(report.path("evaluated").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(report.path("fired").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(report.path("errors").asInt()).isZero();

        JsonNode firing = body(dana().get().uri("/v1/alarms/web-01-cpu").exchange().expectStatus().isOk());
        ContractSchemas.assertValid(ALARM, firing, "the evaluated alarm");
        assertThat(firing.path("state").asText()).isEqualTo("alarm");
        assertThat(firing.path("lastValue").asDouble()).isGreaterThan(0.5);
        assertThat(firing.hasNonNull("lastEvaluatedAt")).isTrue();
        assertThat(Instant.parse(firing.path("stateSince").asText())).isAfter(Instant.parse(changed.path("stateSince").asText()));
        assertThat(body(dana().get().uri("/v1/alarms/api-01-memory").exchange().expectStatus().isOk()).path("state").asText()).isEqualTo("ok");

        // a second evaluation keeps the time the alarm started firing
        service().post().uri("/internal/alarms:evaluate").exchange().expectStatus().isOk();
        JsonNode still = body(dana().get().uri("/v1/alarms/web-01-cpu").exchange().expectStatus().isOk());
        assertThat(still.path("stateSince").asText()).isEqualTo(firing.path("stateSince").asText());

        // firing first; a state filter; one at a time
        JsonNode all = body(dana().get().uri("/v1/alarms").exchange().expectStatus().isOk());
        assertThat(ids(all)).containsExactly("web-01-cpu", "api-01-memory");
        assertThat(all.has("next")).isTrue();
        assertThat(all.path("next").isNull()).isTrue();
        all.path("items").forEach(a -> ContractSchemas.assertValid(ALARM, a, "a listed alarm"));
        assertThat(ids(body(dana().get().uri("/v1/alarms?state=ok").exchange().expectStatus().isOk()))).containsExactly("api-01-memory");
        assertThat(ids(body(dana().get().uri(uri("/v1/alarms?wfrn=" + encode(wfrn))).exchange().expectStatus().isOk()))).containsExactly("web-01-cpu");
        JsonNode first = body(dana().get().uri("/v1/alarms?limit=1").exchange().expectStatus().isOk());
        assertThat(ids(first)).containsExactly("web-01-cpu");
        JsonNode second = body(dana().get().uri(uri("/v1/alarms?limit=1&after=" + encode(first.path("next").asText()))).exchange().expectStatus().isOk());
        assertThat(ids(second)).containsExactly("api-01-memory");
        dana().get().uri("/v1/alarms?state=loud").exchange().expectStatus().isBadRequest();
        dana().get().uri("/v1/alarms?limit=0").exchange().expectStatus().isBadRequest();
        dana().get().uri("/v1/alarms?after=not-a-cursor").exchange().expectStatus().isBadRequest();

        // another account sees none of them
        WebTestClient omar = as(OTHER_ACCOUNT, "omar", "team-data,developer");
        omar.get().uri("/v1/alarms/web-01-cpu").exchange().expectStatus().isNotFound();
        assertThat(body(omar.get().uri("/v1/alarms").exchange().expectStatus().isOk()).path("items")).isEmpty();
        omar.delete().uri("/v1/alarms/web-01-cpu").exchange().expectStatus().isNotFound();

        dana().delete().uri("/v1/alarms/api-01-memory").exchange().expectStatus().isNoContent();
        dana().get().uri("/v1/alarms/api-01-memory").exchange().expectStatus().isNotFound();
        dana().delete().uri("/v1/alarms/api-01-memory").exchange().expectStatus().isNotFound();
        dana().delete().uri("/v1/alarms/web-01-cpu").exchange().expectStatus().isNoContent();
    }

    @Test
    void anAlarmIsOnlyAcceptedOnASeriesOfAResourceOfTheAccount() {
        String wfrn = vm("web-01").path("wfrn").asText();

        assertError(body(put(dana(), "bad.id", alarm(wfrn, "cpuPercent", "above", 80)).expectStatus().isBadRequest()), "");
        JsonNode empty = body(dana().put().uri("/v1/alarms/empty").contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
                .expectStatus().isBadRequest());
        assertError(empty, "");
        assertThat(codes(empty)).contains("wfrn.required", "metric.required", "comparison.required", "threshold.required");
        assertThat(codes(body(put(dana(), "sideways", alarm(wfrn, "cpuPercent", "sideways", 80)).expectStatus().isBadRequest())))
                .containsExactly("comparison.unknown");
        assertThat(codes(body(put(dana(), "no-window", alarm(wfrn, "cpuPercent", "above", 80).put("windowMinutes", 0)).expectStatus().isBadRequest())))
                .containsExactly("windowMinutes.range");
        assertThat(codes(body(put(dana(), "long-window", alarm(wfrn, "cpuPercent", "above", 80).put("windowMinutes", 1441)).expectStatus()
                .isBadRequest()))).containsExactly("windowMinutes.range");
        assertThat(codes(body(put(dana(), "gpu", alarm(wfrn, "gpuPercent", "above", 80)).expectStatus().isBadRequest()))).containsExactly("metric.unknown");
        dana().put().uri("/v1/alarms/broken").contentType(MediaType.APPLICATION_JSON).bodyValue("{\"wfrn\":").exchange().expectStatus().isBadRequest();

        String other = watchOfType(OTHER_ACCOUNT, "database").path("wfrn").asText();
        put(dana(), "elsewhere", alarm(other, "cpuPercent", "above", 80)).expectStatus().isNotFound();
        String volume = watchOfType(ACCOUNT, "volume").path("wfrn").asText();
        assertError(body(put(dana(), "volume", alarm(volume, "cpuPercent", "above", 80)).expectStatus().isNotFound()), "metrics.notSupported");

        // none of them was stored
        assertThat(body(dana().get().uri("/v1/alarms").exchange().expectStatus().isOk()).path("items")).isEmpty();
    }

    private static ObjectNode alarm(String wfrn, String metric, String comparison, double threshold) {
        ObjectNode body = Json.object();
        body.put("wfrn", wfrn);
        body.put("metric", metric);
        body.put("comparison", comparison);
        body.put("threshold", threshold);
        return body;
    }

    private static WebTestClient.ResponseSpec put(WebTestClient client, String alarmId, ObjectNode body) {
        return client.put().uri("/v1/alarms/" + alarmId).contentType(MediaType.APPLICATION_JSON).bodyValue(body.toString()).exchange();
    }

    private static List<String> names(JsonNode metrics) {
        List<String> names = new ArrayList<>();
        metrics.path("series").forEach(s -> names.add(s.path("name").asText()));
        return names;
    }

    private static List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.path("items").forEach(a -> ids.add(a.path("alarmId").asText()));
        return ids;
    }

    private static List<String> codes(JsonNode error) {
        List<String> codes = new ArrayList<>();
        error.path("problems").forEach(p -> codes.add(p.path("code").asText()));
        return codes;
    }
}
