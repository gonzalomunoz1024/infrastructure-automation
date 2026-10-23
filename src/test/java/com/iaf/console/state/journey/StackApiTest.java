package com.iaf.console.state.journey;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** The blueprint and stack routes over HTTP: the bundled catalog, a plan, a launch kept by its idempotency key, a mint for a step, a teardown. */
class StackApiTest extends DemoServiceTest {

    private static String stackPath(String wfrn, String suffix) {
        return "/v1/stacks/" + encode(wfrn) + suffix;
    }

    private ObjectNode launchBody(String name) {
        ObjectNode body = json.createObjectNode().put("blueprint", "dev-sandbox").put("name", name);
        body.putObject("parameters").put("image", fake.demo().imageIds().get(0)).put("scratchGb", 20);
        body.putObject("tags").put("team", "payments");
        return body;
    }

    @Test
    void theCatalogShowsTheBundledBlueprintsWithTheirWaves() {
        JsonNode list = body(as(ACCOUNT, "dana").get().uri("/v1/blueprints").exchange().expectStatus().isOk());
        assertThat(list.get("items").findValuesAsText("name")).contains("web-app", "dev-sandbox");
        JsonNode web = body(as(ACCOUNT, "dana").get().uri("/v1/blueprints/web-app").exchange().expectStatus().isOk());
        assertThat(web.get("waves").toString()).isEqualTo("[[\"image\",\"database\"],[\"web\"],[\"data\"]]");
        assertThat(web.get("resources").get("web").get("service").asText()).isEqualTo("compute");
        as(ACCOUNT, "dana").get().uri("/v1/blueprints/nope").exchange().expectStatus().isNotFound();

        ObjectNode plan = json.createObjectNode().put("name", "planned");
        plan.putObject("parameters").put("image", "img-1");
        JsonNode planned = body(as(ACCOUNT, "dana").post().uri("/v1/blueprints/dev-sandbox:plan").bodyValue(plan).exchange().expectStatus().isOk());
        assertThat(planned.get("steps").get(0).get("input").get("name").asText()).isEqualTo("planned-vm");
        assertThat(planned.get("authorize").findValuesAsText("action")).contains("blueprints:launch", "compute:create");
        ObjectNode missing = json.createObjectNode().put("name", "planned");
        missing.putObject("parameters");
        as(ACCOUNT, "dana").post().uri("/v1/blueprints/dev-sandbox:plan").bodyValue(missing).exchange().expectStatus().isBadRequest();
    }

    @Test
    void aStackLaunchesOnceForItsKeyAndItsStepTakesTheMintedChild() {
        String name = "sbx-" + UUID.randomUUID().toString().substring(0, 8);
        String key = "launch-" + name;
        JsonNode launched = body(as(ACCOUNT, "dana").post().uri("/v1/stacks").header("Idempotency-Key", key).bodyValue(launchBody(name)).exchange()
                .expectStatus().isCreated());
        String wfrn = launched.get("wfrn").asText();
        assertThat(wfrn).startsWith("wfrn:wf:blueprints:global:" + ACCOUNT + ":stack/stack-");
        assertThat(launched.get("state").asText()).isEqualTo("pending");
        JsonNode again = body(as(ACCOUNT, "dana").post().uri("/v1/stacks").header("Idempotency-Key", key).bodyValue(launchBody(name)).exchange()
                .expectStatus().isCreated());
        assertThat(again.get("wfrn").asText()).as("the same key answers the same stack").isEqualTo(wfrn);
        as(ACCOUNT, "dana").post().uri("/v1/stacks").bodyValue(launchBody(name)).exchange().expectStatus().isEqualTo(409);

        JsonNode ready = launched.get("steps").get(0).get("ready");
        ObjectNode mint = json.createObjectNode().put("type", "vm").put("region", "global").put("name", name + "-vm").put("workflow", "create-vm");
        mint.set("input", ready.get("input"));
        mint.putObject("stack").put("wfrn", wfrn).put("step", "vm").put("attempt", ready.get("attempt").asInt());
        JsonNode child = body(as(ACCOUNT, "dana").post().uri("/v1/resources:mint").bodyValue(mint).exchange().expectStatus().isCreated());
        assertThat(child.get("stack").get("wfrn").asText()).isEqualTo(wfrn);

        JsonNode stack = body(as(ACCOUNT, "dana").get().uri(uri(stackPath(wfrn, ""))).exchange().expectStatus().isOk());
        assertThat(stack.get("steps").get(0).get("state").asText()).isEqualTo("running");
        assertThat(stack.get("steps").get(0).get("wfrn").asText()).isEqualTo(child.get("wfrn").asText());
        JsonNode events = body(as(ACCOUNT, "dana").get().uri(uri(stackPath(wfrn, "/events"))).exchange().expectStatus().isOk());
        assertThat(events.get("items").get(0).get("type").asText()).isEqualTo("StackRequested");
        assertThat(events.get("items").get(1).get("type").asText()).isEqualTo("StackStepStarted");
        as(OTHER_ACCOUNT, "erin").get().uri(uri(stackPath(wfrn, ""))).exchange().expectStatus().isNotFound();

        JsonNode list = body(as(ACCOUNT, "dana").get().uri("/v1/stacks?blueprint=dev-sandbox&name=" + name).exchange().expectStatus().isOk());
        assertThat(list.get("items")).hasSize(1);
        assertThat(list.get("items").get(0).get("wfrn").asText()).isEqualTo(wfrn);
    }

    @Test
    void aTeardownChecksTheVersionTheCallerSaw() {
        String name = "td-" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode launched = body(as(ACCOUNT, "dana").post().uri("/v1/stacks").bodyValue(launchBody(name)).exchange().expectStatus().isCreated());
        String wfrn = launched.get("wfrn").asText();
        ObjectNode stale = json.createObjectNode().put("reason", "the sandbox is no longer needed").put("expectedVersion", 99);
        as(ACCOUNT, "dana").post().uri(uri(stackPath(wfrn, ":teardown"))).bodyValue(stale).exchange().expectStatus().isEqualTo(409);

        ObjectNode failure = json.createObjectNode().put("attempt", 1).put("action", "create").put("code", "authz.denied");
        JsonNode failed = body(as(ACCOUNT, "dana").post().uri(uri(stackPath(wfrn, "/steps/vm:report-failure"))).bodyValue(failure).exchange()
                .expectStatus().isOk());
        assertThat(failed.get("state").asText()).isEqualTo("failed");

        ObjectNode teardown = json.createObjectNode().put("reason", "the sandbox is no longer needed")
                .put("expectedVersion", failed.get("version").asLong());
        JsonNode gone = body(as(ACCOUNT, "dana").post().uri(uri(stackPath(wfrn, ":teardown"))).bodyValue(teardown).exchange().expectStatus().isOk());
        assertThat(gone.get("state").asText()).as("nothing was created, so nothing is left to delete").isEqualTo("deleted");
        as(ACCOUNT, "dana").post().uri(uri(stackPath(wfrn, ":resume"))).exchange().expectStatus().isEqualTo(409);
    }
}
