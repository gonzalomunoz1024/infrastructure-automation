package com.iaf.console.state.journey;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.platform.assertion.OnBehalfOfAssertions;
import com.iaf.console.platform.client.Accepted;
import com.iaf.console.platform.client.PlatformClient;
import com.iaf.console.platform.client.PlatformOperation;
import com.iaf.console.platform.client.RequestOptions;
import com.iaf.console.fake.identity.domain.Caller;
import com.iaf.console.fake.identity.domain.DevUsers;
import com.iaf.console.fake.runs.domain.ApprovalRecord;
import com.iaf.console.platform.model.CommandAccepted;
import com.iaf.console.platform.model.JobAccepted;
import com.iaf.console.platform.model.JobStatus;
import com.iaf.console.platform.model.RerunRequest;

/**
 * A VM's whole life as the console drives it: minted here, built by a platform job the BFF starts
 * with the wfrn in its context, observed through the feed, tagged, deleted, and a tombstone after.
 */
class ResourceJourneyTest extends DemoServiceTest {

    private static final OnBehalfOfAssertions.User DANA = new OnBehalfOfAssertions.User("dana", "Dana Reyes", "dana@example.com",
            List.of("team-compute", "developer"));

    private PlatformClient console() {
        return PlatformClient.builder().baseUrl(fake.baseUrl()).bearerToken(fake.demo().compute().token()).build();
    }

    /** Waits until the platform says a job has ended. */
    private void awaitJobEnd(String jobId) {
        awaitJob(jobId, status -> status.terminal(), "end");
    }

    /** Waits until the platform says a job is in a status the test waits for. */
    private void awaitJob(String jobId, java.util.function.Predicate<JobStatus> reached, String what) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            var job = console().getJob(jobId).block(WAIT);
            if (job != null && job.status() != null && reached.test(job.status())) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("job " + jobId + " did not " + what + " within " + WAIT);
    }

    /** What the BFF does after a mint: starts the platform job for the account's user, naming the wfrn. */
    private JobAccepted startJob(PlatformOperation operation, Map<String, String> pathParams, Map<String, Object> input, String wfrn) {
        PlatformClient console = console();
        OnBehalfOfAssertions.Signer signer = new OnBehalfOfAssertions.Signer(fake.verifier().demoKey(), fake.verifier().issuer(),
                fake.verifier().audience());
        RequestOptions options = RequestOptions.none().withOnBehalfOf(signer.sign(DANA, ACCOUNT, "global")).withResourceWfrn(wfrn)
                .withConsoleRequestId("req-" + UUID.randomUUID()).withIdempotencyKey("job-" + UUID.randomUUID());
        Accepted<JobAccepted> accepted = console.start(operation, pathParams, input, options).block(WAIT);
        assertThat(accepted).isNotNull();
        return accepted.body();
    }

    @Test
    void aVmIsRequestedBuiltTaggedDeletedAndRemembered() {
        WebTestClient dana = as(ACCOUNT, "dana");
        String name = "journey-" + UUID.randomUUID().toString().substring(0, 8);
        String image = fake.demo().imageIds().get(0);
        ObjectNode mint = json.createObjectNode().put("type", "vm").put("region", "global").put("name", name).put("workflow", "create-vm");
        mint.putObject("input").put("name", name).put("image", image).put("size", "small").put("environment", "dev");
        JsonNode minted = body(dana.post().uri("/v1/resources:mint").header("Idempotency-Key", "mint-" + name).bodyValue(mint).exchange()
                .expectStatus().isCreated());
        String wfrn = minted.get("wfrn").asText();
        assertThat(minted.get("state").asText()).isEqualTo("pending");
        assertThat(minted.get("requestedBy").asText()).isEqualTo("dana");

        JobAccepted create = startJob(PlatformOperation.CREATE_VM, Map.of(), Map.of("name", name, "image", image, "size", "small", "environment", "dev"),
                wfrn);
        JsonNode active = await(() -> getResource(dana, wfrn), r -> "active".equals(r.get("state").asText()), "the VM to become active");
        assertThat(active.get("lastJobId").asText()).isEqualTo(create.jobId());
        assertThat(active.get("registryId").asText()).isNotBlank();
        assertThat(active.get("attributes").get("name").asText()).isEqualTo(name);
        assertThat(active.get("version").asLong()).isEqualTo(3);
        long activeVersion = active.get("version").asLong();

        JsonNode events = body(dana.get().uri(resource(wfrn, "/events")).exchange().expectStatus().isOk());
        List<String> types = new ArrayList<>();
        events.get("items").forEach(e -> types.add(e.get("type").asText()));
        assertThat(types).startsWith("ResourceRequested", "ProvisioningStarted", "ProvisioningSucceeded");
        assertThat(events.get("items").get(1).get("data").get("jobId").asText()).isEqualTo(create.jobId());

        JsonNode listed = body(dana.get().uri("/v1/resources?type=vm&name=" + name).exchange().expectStatus().isOk());
        assertThat(listed.get("items")).hasSize(1);
        assertThat(listed.get("items").get(0).get("wfrn").asText()).isEqualTo(wfrn);

        ObjectNode tag = json.createObjectNode().put("expectedVersion", activeVersion).put("actor", "mallory");
        tag.putObject("set").put("env", "dev").put("owner-team", "web");
        JsonNode tagged = body(dana.post().uri(resource(wfrn, ":tag")).bodyValue(tag).exchange().expectStatus().isOk());
        assertThat(tagged.get("tags").get("owner-team").asText()).isEqualTo("web");
        JsonNode tagEvents = body(dana.get().uri(resource(wfrn, "/events")).exchange().expectStatus().isOk());
        JsonNode lastTag = tagEvents.get("items").get(tagEvents.get("items").size() - 1);
        assertThat(lastTag.toString()).as("a person's body never names another actor").doesNotContain("\"mallory\"");
        JsonNode byTag = body(dana.get().uri("/v1/resources?tag.owner-team=web&type=vm").exchange().expectStatus().isOk());
        assertThat(byTag.get("items")).extracting(r -> r.get("wfrn").asText()).contains(wfrn);

        ObjectNode observation = json.createObjectNode().put("status", "found").put("provider", "vsphere").put("action", "vm.get")
                .put("observedAt", java.time.Instant.now().toString());
        observation.putObject("observed").put("name", name).put("powerState", "poweredOn");
        JsonNode observed = body(asService("console-drift-service").post().uri(uri("/v1/wfrn/" + encode(wfrn) + ":record-observation?accountId=" + ACCOUNT))
                .bodyValue(observation).exchange().expectStatus().isOk());
        assertThat(observed.get("observedStatus").asText()).isEqualTo("found");
        dana.post().uri(resource(wfrn, ":record-observation")).bodyValue(observation.put("status", "not_found")).exchange().expectStatus()
                .isForbidden();
        observation.put("status", "found");
        assertThat(observed.get("observed").get("powerState").asText()).isEqualTo("poweredOn");

        // an update in the BFF's order: the platform job first, the request after it, naming the job; here the job is over first
        String registryId = active.get("registryId").asText();
        JobAccepted resize = startJob(PlatformOperation.UPDATE_VM, Map.of("vmId", registryId), Map.of("vmId", registryId, "cpu", 4, "memoryGb", 16),
                wfrn);
        awaitJobEnd(resize.jobId());
        ObjectNode update = json.createObjectNode().put("workflow", "update-vm").put("jobId", resize.jobId());
        update.putObject("changes").put("cpu", 4).put("memoryGb", 16);
        JsonNode updated = body(dana.post().uri(resource(wfrn, ":request-update")).header("Idempotency-Key", "resize-" + name).bodyValue(update)
                .exchange().expectStatus().isOk());
        assertThat(updated.get("state").asText()).as("the job it names had already ended").isEqualTo("active");
        assertThat(updated.get("lastJobId").asText()).isEqualTo(resize.jobId());
        assertThat(updated.get("attributes").get("cpu").asInt()).isEqualTo(4);
        assertThat(updated.get("attributes").get("memoryGb").asInt()).isEqualTo(16);

        ObjectNode delete = json.createObjectNode().put("workflow", "delete-vm").put("reason", "end of the journey");
        JsonNode deleting = body(dana.post().uri(resource(wfrn, ":request-delete")).bodyValue(delete).exchange().expectStatus().isOk());
        assertThat(deleting.get("state").asText()).isEqualTo("deleting");
        JobAccepted deletion = startJob(PlatformOperation.DELETE_VM, Map.of("vmId", active.get("registryId").asText()),
                Map.of("vmId", active.get("registryId").asText(), "reason", "end of the journey"), wfrn);
        approve(deletion.jobId());

        JsonNode tombstone = await(() -> {
            var result = dana.get().uri(resource(wfrn)).exchange().returnResult(JsonNode.class);
            return result.getStatus().value() == 410 ? result.getResponseBody().blockFirst(WAIT) : null;
        }, t -> t.has("tombstoneUntil"), "the tombstone");
        assertThat(tombstone.get("wfrn").asText()).isEqualTo(wfrn);
        assertThat(tombstone.get("error").asText()).isNotBlank();
        assertThat(tombstone.get("lastVersion").asLong()).isGreaterThan(activeVersion);

        JsonNode asOf = body(dana.get().uri(resource(wfrn, "?asOf=" + activeVersion)).exchange().expectStatus().isOk());
        assertThat(asOf.get("state").asText()).isEqualTo("active");
        assertThat(asOf.get("tags")).isEmpty();
        JsonNode trail = body(dana.get().uri(resource(wfrn, "/events?after=" + activeVersion)).exchange().expectStatus().isOk());
        List<String> later = new ArrayList<>();
        trail.get("items").forEach(e -> later.add(e.get("type").asText()));
        // the update's job ended before its request (the feed may have recorded its registration first); the delete was
        // requested before its job, whose start is recorded like any job's
        assertThat(later).startsWith("Tagged", "Observed").containsSubsequence("UpdateRequested", "ProvisioningSucceeded", "DeleteRequested",
                "ProvisioningStarted", "Deleted").endsWith("Deleted");
        assertThat(body(dana.get().uri("/v1/resources?type=vm&name=" + name).exchange().expectStatus().isOk()).get("items")).isEmpty();
        assertThat(body(dana.get().uri("/v1/resources?type=vm&name=" + name + "&state=deleted").exchange().expectStatus().isOk()).get("items")).hasSize(1);
        dana.post().uri(resource(wfrn, ":tag")).bodyValue(tag).exchange().expectStatus().isEqualTo(410);
    }
    @Test
    void aFailedCreateIsRetriedByRerunningItsJob() {
        WebTestClient dana = as(ACCOUNT, "dana");
        String name = "retry-" + UUID.randomUUID().toString().substring(0, 8);
        String image = fake.demo().imageIds().get(0);
        ObjectNode mint = json.createObjectNode().put("type", "vm").put("region", "global").put("name", name).put("workflow", "create-vm");
        mint.putObject("input").put("name", name).put("image", image).put("size", "small").put("environment", "dev");
        String wfrn = body(dana.post().uri("/v1/resources:mint").bodyValue(mint).exchange().expectStatus().isCreated()).get("wfrn").asText();

        ObjectNode noCapacity = json.createObjectNode().put("provider", "vsphere").put("action", "vm.create");
        noCapacity.putObject("fail").put("class", "provider.capacity").put("message", "no capacity left in the cluster").put("retryable", false)
                .put("status", 409).put("times", 1);
        fake.world().mutate(noCapacity);
        JobAccepted create = startJob(PlatformOperation.CREATE_VM, Map.of(), Map.of("name", name, "image", image, "size", "small", "environment", "dev"),
                wfrn);
        // the platform pauses a failed step for a decision; the user gives up on it, and the create fails
        awaitJob(create.jobId(), status -> status == JobStatus.NEEDS_ATTENTION, "stop at the failed step");
        assertThat(console().abortJob(create.jobId(), onBehalfOfDana(wfrn, "abort-")).block(WAIT)).isNotNull();
        JsonNode failed = await(() -> getResource(dana, wfrn), r -> "failed".equals(r.get("state").asText()), "the create to fail");
        assertThat(failed.get("lastError").get("jobId").asText()).isEqualTo(create.jobId());
        assertThat(failed.get("lastError").get("failedStep").asText()).isEqualTo("createVm");

        // the BFF's order: the platform reruns the failed job from its failed step (the wfrn stays its context), then the retry names the rerun
        String rerun = rerun(create.jobId(), failed.get("lastError").get("failedStep").asText(), wfrn);
        JsonNode retried = body(dana.post().uri(resource(wfrn, ":request-retry")).header("Idempotency-Key", "retry-" + name)
                .bodyValue(json.createObjectNode().put("jobId", rerun)).exchange().expectStatus().isOk());
        assertThat(retried.get("state").asText()).as("pending until the rerun ends, unless it already has").isIn("pending", "active");
        assertThat(retried.get("lastJobId").asText()).isEqualTo(rerun);
        JsonNode active = await(() -> getResource(dana, wfrn), r -> "active".equals(r.get("state").asText()), "the rerun to make the VM active");
        assertThat(active.get("lastJobId").asText()).isEqualTo(rerun);
        assertThat(active.path("lastError").isMissingNode() || active.path("lastError").isNull()).as("the failure is behind it").isTrue();
        assertThat(active.get("registryId").asText()).isNotBlank();

        JsonNode events = body(dana.get().uri(resource(wfrn, "/events")).exchange().expectStatus().isOk());
        List<String> types = new ArrayList<>();
        events.get("items").forEach(e -> types.add(e.get("type").asText()));
        assertThat(types).startsWith("ResourceRequested", "ProvisioningStarted", "ProvisioningFailed").endsWith("ProvisioningSucceeded")
                .containsOnlyOnce("ProvisioningFailed");
        dana.post().uri(resource(wfrn, ":request-retry")).exchange().expectStatus().isEqualTo(409);
    }

    /** What the BFF does to retry: reruns the failed job from a step for the account's user; answers the new job's id. */
    private String rerun(String jobId, String fromStep, String wfrn) {
        Accepted<CommandAccepted> accepted = console().rerunJob(jobId, new RerunRequest(fromStep, null), onBehalfOfDana(wfrn, "rerun-")).block(WAIT);
        assertThat(accepted).isNotNull();
        assertThat(accepted.body().id()).isNotEqualTo(jobId);
        return accepted.body().id();
    }

    /** How the BFF calls the platform for the account's user about one resource. */
    private RequestOptions onBehalfOfDana(String wfrn, String keyPrefix) {
        OnBehalfOfAssertions.Signer signer = new OnBehalfOfAssertions.Signer(fake.verifier().demoKey(), fake.verifier().issuer(),
                fake.verifier().audience());
        return RequestOptions.none().withOnBehalfOf(signer.sign(DANA, ACCOUNT, "global")).withResourceWfrn(wfrn)
                .withConsoleRequestId("req-" + UUID.randomUUID()).withIdempotencyKey(keyPrefix + UUID.randomUUID());
    }

    /** A delete is destructive and waits for an approval on the platform; an approver of the group gives it, as the demo seed does. */
    private void approve(String jobId) {
        Caller admin = DevUsers.byName(DevUsers.DEFAULT);
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            for (ApprovalRecord approval : fake.jobs().approvals("pending", admin)) {
                if (jobId.equals(approval.jobId())) {
                    Caller approver = admin;
                    for (String name : List.of("mia", "raj")) {
                        Caller candidate = DevUsers.byName(name);
                        if (candidate.inGroup(approval.group())) {
                            approver = candidate;
                        }
                    }
                    fake.jobs().decideApproval(approval, true, approver, "approved by the journey test");
                    return;
                }
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("job " + jobId + " never asked for an approval");
    }

    /** The image a VM is built from: imported into the account unless the boot import brought it already. */
    private String imageWfrn(WebTestClient client, String image) {
        JsonNode images = body(client.get().uri("/v1/resources?type=image&limit=200").exchange().expectStatus().isOk());
        for (JsonNode r : images.get("items")) {
            if (image.equals(r.path("registryId").asText())) {
                return r.get("wfrn").asText();
            }
        }
        ObjectNode body = json.createObjectNode().put("type", "image").put("region", "global").put("registryId", image).put("name", "golden-image")
                .put("source", "discover");
        client.post().uri("/v1/resources:import").bodyValue(body).exchange().expectStatus().isForbidden();
        return body(asService("console-drift-service").post().uri("/v1/resources:import?accountId=" + ACCOUNT).bodyValue(body).exchange()
                .expectStatus().isCreated()).get("wfrn").asText();
    }

    @Test
    void aVmBuiltFromAnImageHasItsLineageTrailAndPast() {
        WebTestClient dana = as(ACCOUNT, "dana");
        String image = fake.demo().imageIds().get(0);
        String imageWfrn = imageWfrn(dana, image);
        java.time.Instant beforeMint = java.time.Instant.now();
        String name = "lineage-" + UUID.randomUUID().toString().substring(0, 8);
        ObjectNode mint = json.createObjectNode().put("type", "vm").put("region", "global").put("name", name).put("workflow", "create-vm");
        mint.putObject("input").put("name", name).put("image", image).put("size", "small").put("environment", "dev");
        String wfrn = body(dana.post().uri("/v1/resources:mint").bodyValue(mint).exchange().expectStatus().isCreated()).get("wfrn").asText();
        startJob(PlatformOperation.CREATE_VM, Map.of(), Map.of("name", name, "image", image, "size", "small", "environment", "dev"), wfrn);
        JsonNode active = await(() -> getResource(dana, wfrn), r -> "active".equals(r.get("state").asText()), "the VM to become active");
        assertThat(active.get("builtFrom")).extracting(JsonNode::asText).containsExactly(image);
        assertThat(active.get("eventHash").asText()).startsWith("sha256:");

        // lineage, blast radius and the map
        JsonNode lineage = body(dana.get().uri(resource(wfrn, "/lineage")).exchange().expectStatus().isOk());
        assertThat(lineage.get("builtFrom")).extracting(r -> r.get("wfrn").asText()).containsExactly(imageWfrn);
        assertThat(lineage.get("edges")).anySatisfy(e -> {
            assertThat(e.get("from").asText()).isEqualTo(imageWfrn);
            assertThat(e.get("to").asText()).isEqualTo(wfrn);
            assertThat(e.get("via").asText()).isEqualTo("create-vm");
        });
        dana.get().uri(resource(wfrn, "/lineage?depth=0")).exchange().expectStatus().isBadRequest();

        JsonNode radius = body(dana.get().uri(resource(imageWfrn, "/blast-radius")).exchange().expectStatus().isOk());
        assertThat(radius.get("total").asInt()).isPositive();
        assertThat(radius.get("items")).extracting(r -> r.get("wfrn").asText()).contains(wfrn);
        assertThat(radius.get("accounts").get(0).get("accountId").asText()).isEqualTo(ACCOUNT);
        assertThat(radius.get("accounts").get(0).get("yours").asBoolean()).isTrue();
        ObjectNode bulk = json.createObjectNode();
        bulk.putArray("wfrns").add(imageWfrn);
        JsonNode bulkRadius = body(dana.post().uri("/v1/resources:blast-radius").bodyValue(bulk).exchange().expectStatus().isOk());
        assertThat(bulkRadius.get("total").asInt()).isEqualTo(radius.get("total").asInt());
        dana.post().uri("/v1/resources:blast-radius").bodyValue(json.createObjectNode().putArray("wfrns")).exchange().expectStatus().isBadRequest();

        JsonNode map = body(dana.get().uri("/v1/graph?type=vm&limit=500").exchange().expectStatus().isOk());
        assertThat(map.get("nodes")).anySatisfy(n -> assertThat(n.get("wfrn").asText()).isEqualTo(wfrn));
        assertThat(map.get("nodes")).anySatisfy(n -> {
            assertThat(n.get("wfrn").asText()).isEqualTo(imageWfrn);
            assertThat(n.get("context").asBoolean()).isTrue();
        });
        assertThat(map.get("edges")).anySatisfy(e -> assertThat(e.get("to").asText()).isEqualTo(wfrn));
        dana.get().uri("/v1/graph?accountId=" + OTHER_ACCOUNT).exchange().expectStatus().isNotFound();

        // the trail
        JsonNode activity = body(dana.get().uri(uri("/v1/activity?limit=10&wfrn=" + encode(wfrn))).exchange().expectStatus().isOk());
        assertThat(activity.get("items").get(0).get("type").asText()).isEqualTo("ProvisioningSucceeded");
        assertThat(activity.get("items").get(0).get("chain").get("hash").asText()).isEqualTo(active.get("eventHash").asText());
        JsonNode check = body(dana.get().uri(resource(wfrn, "/events:verify")).exchange().expectStatus().isOk());
        assertThat(check.get("valid").asBoolean()).isTrue();
        assertThat(check.get("chained").asInt()).isEqualTo(3);
        JsonNode keys = body(dana.get().uri("/v1/activity/keys").exchange().expectStatus().isOk());
        assertThat(keys.get("keys").get(0).get("alg").asText()).isEqualTo("ES256");
        String window = "&from=" + beforeMint.minusSeconds(3600) + "&to=" + java.time.Instant.now().plusSeconds(60);
        dana.get().uri("/v1/activity/evidence?accountId=" + ACCOUNT + window).exchange().expectStatus().isForbidden();
        JsonNode pack = body(asAdmin(ACCOUNT, "ana").get().uri("/v1/activity/evidence?accountId=" + ACCOUNT + window).exchange().expectStatus().isOk());
        assertThat(pack.get("verification").get("valid").asBoolean()).isTrue();
        assertThat(pack.get("events")).anySatisfy(e -> assertThat(e.get("wfrn").asText()).isEqualTo(wfrn));

        // the past
        dana.get().uri(uri("/v1/wfrn/" + encode(wfrn) + "?at=" + beforeMint.minusSeconds(1))).exchange().expectStatus().isNotFound();
        JsonNode now = body(dana.get().uri(uri("/v1/wfrn/" + encode(wfrn) + "?at=" + java.time.Instant.now())).exchange().expectStatus().isOk());
        assertThat(now.get("state").asText()).isEqualTo("active");
        JsonNode account = body(dana.get().uri(uri("/v1/resources?type=vm&limit=200&at=" + java.time.Instant.now())).exchange().expectStatus().isOk());
        assertThat(account.get("items")).extracting(r -> r.get("wfrn").asText()).contains(wfrn);
        ObjectNode tag = json.createObjectNode();
        tag.putObject("set").put("owner-team", "web");
        dana.post().uri(resource(wfrn, ":tag")).bodyValue(tag).exchange().expectStatus().isOk();
        JsonNode plan = body(dana.get().uri(resource(wfrn, "/revert-plan?version=" + active.get("version").asLong())).exchange().expectStatus().isOk());
        assertThat(plan.get("possible").asBoolean()).isTrue();
        assertThat(plan.get("tags").get("removed")).extracting(JsonNode::asText).containsExactly("owner-team");
        dana.get().uri(resource(wfrn, "/revert-plan")).exchange().expectStatus().isBadRequest();
    }

    @Test
    void aVmMovesToAnotherAccountAndItsOldNameSaysWhereItWent() {
        WebTestClient dana = as(ACCOUNT, "dana");
        String image = fake.demo().imageIds().get(0);
        String name = "move-" + UUID.randomUUID().toString().substring(0, 8);
        ObjectNode mint = json.createObjectNode().put("type", "vm").put("region", "global").put("name", name).put("workflow", "create-vm");
        mint.putObject("input").put("name", name).put("image", image).put("size", "small").put("environment", "dev");
        String wfrn = body(dana.post().uri("/v1/resources:mint").bodyValue(mint).exchange().expectStatus().isCreated()).get("wfrn").asText();
        startJob(PlatformOperation.CREATE_VM, Map.of(), Map.of("name", name, "image", image, "size", "small", "environment", "dev"), wfrn);
        JsonNode active = await(() -> getResource(dana, wfrn), r -> "active".equals(r.get("state").asText()), "the VM to become active");

        ObjectNode move = json.createObjectNode().put("toAccountId", OTHER_ACCOUNT).put("reason", "the team moved").put("actor", "dana");
        java.net.URI transfer = uri("/v1/wfrn/" + encode(wfrn) + ":transfer?accountId=" + ACCOUNT);
        WebTestClient bff = asService("console-bff");
        dana.post().uri(resource(wfrn, ":transfer")).bodyValue(move).exchange().expectStatus().isForbidden();
        bff.post().uri(transfer).bodyValue(json.createObjectNode().put("toAccountId", ACCOUNT)).exchange()
                .expectStatus().isBadRequest();
        JsonNode moved = body(bff.post().uri(transfer).header("Idempotency-Key", "move-" + name).bodyValue(move).exchange()
                .expectStatus().isOk());
        String there = moved.get("wfrn").asText();
        assertThat(there).contains(":" + OTHER_ACCOUNT + ":vm/").isNotEqualTo(wfrn);
        assertThat(moved.get("transferredFrom").asText()).isEqualTo(wfrn);
        assertThat(moved.get("source").asText()).isEqualTo("transfer");
        assertThat(moved.get("registryId").asText()).isEqualTo(active.get("registryId").asText());
        assertThat(moved.get("version").asInt()).isEqualTo(1);
        bff.post().uri(transfer).header("Idempotency-Key", "move-" + name).bodyValue(move).exchange()
                .expectStatus().isOk().expectHeader().valueEquals("Idempotent-Replay", "true");

        JsonNode gone = body(dana.get().uri(resource(wfrn)).exchange().expectStatus().isEqualTo(410));
        assertThat(gone.get("error").asText()).isEqualTo("resource.transferred");
        assertThat(gone.get("transferredTo").asText()).isEqualTo(there);
        assertThat(gone.has("tombstoneUntil")).isFalse();
        dana.get().uri(resource(there)).exchange().expectStatus().isNotFound();
        JsonNode arrived = getResource(as(OTHER_ACCOUNT, "omar"), there);
        assertThat(arrived.get("state").asText()).isEqualTo("active");
        assertThat(arrived.get("builtFrom")).extracting(JsonNode::asText).containsExactly(image);

        JsonNode trail = body(dana.get().uri("/v1/activity?type=Transferred").exchange().expectStatus().isOk());
        assertThat(trail.get("items")).anySatisfy(e -> {
            assertThat(e.get("aggregateId").asText()).isEqualTo(wfrn);
            assertThat(e.get("data").get("transferredTo").asText()).isEqualTo(there);
            assertThat(e.get("actor").asText()).isEqualTo("dana");
        });
        dana.post().uri(resource(wfrn, ":tag")).bodyValue(json.createObjectNode().putObject("set").put("a", "b")).exchange()
                .expectStatus().isEqualTo(410);
    }
}
