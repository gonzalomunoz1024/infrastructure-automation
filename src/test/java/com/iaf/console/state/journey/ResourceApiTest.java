package com.iaf.console.state.journey;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** The HTTP contract: scope, encoded wfrns in paths, errors, idempotency keys, masking, internal routes. */
class ResourceApiTest extends DemoServiceTest {

    private ObjectNode mintBody(String type, String name) {
        ObjectNode body = json.createObjectNode().put("type", type).put("region", "global").put("name", name).put("workflow", "create-" + type);
        ObjectNode input = body.putObject("input");
        input.put("name", name);
        if ("database".equals(type)) {
            input.put("engine", "postgres").put("secret", "hunter2");
        } else {
            input.put("image", fake.demo().imageIds().get(0)).put("size", "small").put("environment", "dev");
        }
        return body;
    }

    private JsonNode mint(WebTestClient client, ObjectNode body, String key) {
        WebTestClient.RequestBodySpec request = client.post().uri("/v1/resources:mint");
        if (key != null) {
            request = request.header("Idempotency-Key", key);
        }
        return body(request.bodyValue(body).exchange().expectStatus().isCreated());
    }

    @Test
    void anAccountSeesOnlyItsOwnResources() {
        JsonNode mine = body(as(ACCOUNT, "dana").get().uri("/v1/resources?limit=200").exchange().expectStatus().isOk());
        assertThat(mine.get("items")).as("the demo seed was imported at boot").isNotEmpty();
        for (JsonNode r : mine.get("items")) {
            assertThat(r.get("accountId").asText()).isEqualTo(ACCOUNT);
            assertThat(r.get("state").asText()).isNotEqualTo("deleted");
        }
        JsonNode page = body(as(ACCOUNT, "dana").get().uri("/v1/resources?limit=1").exchange().expectStatus().isOk());
        assertThat(page.get("items")).hasSize(1);
        if (mine.get("items").size() > 1) {
            assertThat(page.get("next").asText()).isNotBlank();
            JsonNode following = body(as(ACCOUNT, "dana").get().uri("/v1/resources?limit=1&after=" + page.get("next").asText()).exchange()
                    .expectStatus().isOk());
            assertThat(following.get("items").get(0).get("wfrn").asText()).isNotEqualTo(page.get("items").get(0).get("wfrn").asText());
        }
        JsonNode vms = body(as(ACCOUNT, "dana").get().uri("/v1/resources?type=vm&sort=name").exchange().expectStatus().isOk());
        for (JsonNode r : vms.get("items")) {
            assertThat(r.get("type").asText()).isEqualTo("vm");
        }
        as(ACCOUNT, "dana").get().uri("/v1/resources?limit=0").exchange().expectStatus().isBadRequest();
        as(ACCOUNT, "dana").get().uri("/v1/resources?limit=201").exchange().expectStatus().isBadRequest();
        as(ACCOUNT, "dana").get().uri("/v1/resources?state=purple").exchange().expectStatus().isBadRequest();
        as(ACCOUNT, "dana").get().uri("/v1/resources?wfrn=not a pattern").exchange().expectStatus().isBadRequest();
    }

    @Test
    void withoutACallerThereIsNothing() {
        anonymous().get().uri("/v1/resources").exchange().expectStatus().isUnauthorized();
        anonymous().get().uri("/actuator/health").exchange().expectStatus().isOk();
        anonymous().get().uri("/internal/feed").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void encodedWfrnsRouteToTheResourceItsEventsAndItsCommands() {
        JsonNode minted = mint(as(ACCOUNT, "dana"), mintBody("vm", "api-" + UUID.randomUUID()), null);
        String wfrn = minted.get("wfrn").asText();
        assertThat(wfrn).startsWith("wfrn:wf:compute:global:" + ACCOUNT + ":vm/vm-");
        assertThat(minted.get("state").asText()).isEqualTo("pending");
        assertThat(minted.get("version").asLong()).isEqualTo(1);
        assertThat(encode(wfrn)).contains("%3A").contains("%2F").doesNotContain(":").doesNotContain("/");

        WebTestClient.ResponseSpec got = as(ACCOUNT, "dana").get().uri(resource(wfrn)).exchange().expectStatus().isOk()
                .expectHeader().valueEquals("ETag", "\"1\"");
        assertThat(body(got).get("wfrn").asText()).isEqualTo(wfrn);
        as(ACCOUNT, "dana").get().uri(resource(wfrn)).header("If-None-Match", "\"1\"").exchange().expectStatus().isNotModified();

        JsonNode events = body(as(ACCOUNT, "dana").get().uri(resource(wfrn, "/events")).exchange().expectStatus().isOk());
        assertThat(events.get("items")).hasSize(1);
        assertThat(events.get("items").get(0).get("type").asText()).isEqualTo("ResourceRequested");
        assertThat(events.get("items").get(0).get("key").asText()).isEqualTo("ResourceRequested:" + wfrn + ":1");
        JsonNode entry = events.get("items").get(0);
        assertThat(entry.get("id").asText()).startsWith("evt_");
        assertThat(entry.get("specVersion").asText()).isEqualTo("1.0");
        assertThat(entry.get("aggregate").asText()).isEqualTo("resource");
        assertThat(entry.get("aggregateId").asText()).isEqualTo(wfrn);
        assertThat(entry.get("context").get("accountId").asText()).isEqualTo(ACCOUNT);
        assertThat(entry.get("context").get("wfrn").asText()).isEqualTo(wfrn);
        assertThat(entry.get("data").get("version").asLong()).isEqualTo(1);
        assertThat(entry.get("data").get("state").asText()).isEqualTo("pending");
        assertThat(entry.get("data").get("resourceType").asText()).isEqualTo("vm");

        ObjectNode tag = json.createObjectNode();
        tag.putObject("set").put("env", "dev");
        JsonNode tagged = body(as(ACCOUNT, "dana").post().uri(resource(wfrn, ":tag")).bodyValue(tag).exchange().expectStatus().isOk());
        assertThat(tagged.get("version").asLong()).isEqualTo(2);
        assertThat(tagged.get("tags").get("env").asText()).isEqualTo("dev");

        ObjectNode stale = json.createObjectNode().put("expectedVersion", 1);
        stale.putObject("set").put("env", "prod");
        JsonNode conflict = body(as(ACCOUNT, "dana").post().uri(resource(wfrn, ":tag")).bodyValue(stale).exchange().expectStatus().isEqualTo(409));
        assertThat(conflict.toString()).contains("version.mismatch");

        ObjectNode badName = json.createObjectNode();
        badName.putObject("set").put("Env", "prod");
        as(ACCOUNT, "dana").post().uri(resource(wfrn, ":tag")).bodyValue(badName).exchange().expectStatus().isBadRequest();

        ObjectNode update = json.createObjectNode().put("workflow", "update-vm");
        update.putObject("changes").put("size", "medium");
        JsonNode busy = body(as(ACCOUNT, "dana").post().uri(resource(wfrn, ":request-update")).bodyValue(update).exchange().expectStatus().isEqualTo(409));
        assertThat(busy.toString()).contains("resource.busy");

        as(OTHER_ACCOUNT, "lee").get().uri(resource(wfrn)).exchange().expectStatus().isNotFound();
        as(OTHER_ACCOUNT, "lee").post().uri(resource(wfrn, ":tag")).bodyValue(tag).exchange().expectStatus().isNotFound();
        as(ACCOUNT, "dana").get().uri(resource("wfrn:wf:compute:global:" + ACCOUNT + ":vm/vm-00000000000000000000000000")).exchange()
                .expectStatus().isNotFound();
        as(ACCOUNT, "dana").get().uri(resource("not-a-wfrn")).exchange().expectStatus().isBadRequest();
    }

    @Test
    void anIdempotencyKeyReplaysTheFirstAnswer() {
        String key = "mint-" + UUID.randomUUID();
        ObjectNode body = mintBody("vm", "idem-" + UUID.randomUUID());
        JsonNode first = mint(as(ACCOUNT, "dana"), body, key);
        WebTestClient.ResponseSpec again = as(ACCOUNT, "dana").post().uri("/v1/resources:mint").header("Idempotency-Key", key).bodyValue(body).exchange()
                .expectStatus().isCreated().expectHeader().valueEquals("Idempotent-Replay", "true");
        assertThat(body(again).get("wfrn").asText()).isEqualTo(first.get("wfrn").asText());
        ObjectNode other = body.deepCopy().put("name", "something-else");
        as(ACCOUNT, "dana").post().uri("/v1/resources:mint").header("Idempotency-Key", key).bodyValue(other).exchange().expectStatus().isEqualTo(409);
        JsonNode otherAccount = mint(as(OTHER_ACCOUNT, "lee"), body, key);
        assertThat(otherAccount.get("wfrn").asText()).as("keys are per account").isNotEqualTo(first.get("wfrn").asText());
    }

    @Test
    void secretsAreMaskedUnlessRevealedToAnAdminOrAService() {
        JsonNode minted = mint(as(OTHER_ACCOUNT, "lee"), mintBody("database", "db-" + UUID.randomUUID()), null);
        String wfrn = minted.get("wfrn").asText();
        assertThat(minted.get("attributes").get("secret").asText()).isEqualTo("***");
        assertThat(minted.get("attributes").get("engine").asText()).isEqualTo("postgres");
        assertThat(getResource(as(OTHER_ACCOUNT, "lee"), wfrn).get("attributes").get("secret").asText()).isEqualTo("***");
        JsonNode events = body(as(OTHER_ACCOUNT, "lee").get().uri(resource(wfrn, "/events")).exchange().expectStatus().isOk());
        assertThat(events.get("items").get(0).get("data").get("input").get("secret").asText()).isEqualTo("***");
        JsonNode ignored = body(as(OTHER_ACCOUNT, "lee").get().uri(resource(wfrn, "?reveal=sensitive")).exchange().expectStatus().isOk());
        assertThat(ignored.get("attributes").get("secret").asText()).isEqualTo("***");
        JsonNode revealed = body(asAdmin(OTHER_ACCOUNT, "lee").get().uri(resource(wfrn, "?reveal=sensitive")).exchange().expectStatus().isOk());
        assertThat(revealed.get("attributes").get("secret").asText()).isEqualTo("hunter2");
        JsonNode toService = body(asService("console-bff").get().uri(resource(wfrn, "?reveal=sensitive")).exchange().expectStatus().isOk());
        assertThat(toService.get("attributes").get("secret").asText()).isEqualTo("hunter2");
        JsonNode log = body(asAdmin(OTHER_ACCOUNT, "lee").get().uri(resource(wfrn, "/events?reveal=sensitive")).exchange().expectStatus().isOk());
        assertThat(log.get("items").get(0).get("data").get("input").get("secret").asText()).isEqualTo("hunter2");
        JsonNode listed = body(as(OTHER_ACCOUNT, "lee").get().uri("/v1/resources?type=database&name=db-").exchange().expectStatus().isOk());
        for (JsonNode r : listed.get("items")) {
            assertThat(r.get("attributes").get("secret").asText()).isEqualTo("***");
        }
    }

    @Test
    void internalRoutesAreForServicesOnly() {
        as(ACCOUNT, "dana").get().uri("/internal/feed").exchange().expectStatus().isNotFound();
        as(ACCOUNT, "dana").post().uri("/internal/reap").exchange().expectStatus().isNotFound();
        JsonNode feed = body(asService("console-bff").get().uri("/internal/feed").exchange().expectStatus().isOk());
        assertThat(feed).hasSize(1);
        assertThat(feed.get(0).get("name").asText()).isEqualTo("http");
        assertThat(feed.get(0).get("lastSeq").asLong()).isGreaterThan(0);
        assertThat(feed.get(0).get("running").asBoolean()).isTrue();
        JsonNode reaped = body(asService("console-bff").post().uri("/internal/reap").exchange().expectStatus().isOk());
        assertThat(reaped.has("checked")).isTrue();
        asService("console-bff").post().uri("/v1/resources:mint").bodyValue(mintBody("vm", "svc")).exchange().expectStatus().isBadRequest();
        JsonNode scoped = body(asService("console-bff").get().uri("/v1/resources?accountId=" + ACCOUNT + "&limit=5").exchange().expectStatus().isOk());
        for (JsonNode r : scoped.get("items")) {
            assertThat(r.get("accountId").asText()).isEqualTo(ACCOUNT);
        }
        asService("console-bff").get().uri("/v1/resources").exchange().expectStatus().isBadRequest();
        JsonNode imported = body(asService("console-bff").post().uri("/internal/import")
                .bodyValue(Map.of("accountId", ACCOUNT, "region", "global", "owner", fake.demo().compute().team())).exchange().expectStatus().isOk());
        assertThat(imported.get("seen").asLong()).isGreaterThan(0);
        JsonNode withoutBackfill = body(asService("console-bff").post().uri("/internal/import").bodyValue(Map.of("accountId", ACCOUNT, "region", "global"))
                .exchange().expectStatus().isOk());
        assertThat(withoutBackfill.get("seen").asLong()).isEqualTo(imported.get("seen").asLong());
    }
}
