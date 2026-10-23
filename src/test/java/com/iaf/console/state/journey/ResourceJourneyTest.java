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
import com.iaf.console.platform.model.JobAccepted;

/**
 * A VM's whole life as the console drives it: minted here, built by a platform job the BFF starts
 * with the wfrn in its context, observed through the feed, tagged, deleted, and a tombstone after.
 */
class ResourceJourneyTest extends DemoServiceTest {

    private static final OnBehalfOfAssertions.User DANA = new OnBehalfOfAssertions.User("dana", "Dana Reyes", "dana@example.com",
            List.of("team-compute", "developer"));

    /** What the BFF does after a mint: starts the platform job for the account's user, naming the wfrn. */
    private JobAccepted startJob(PlatformOperation operation, Map<String, String> pathParams, Map<String, Object> input, String wfrn) {
        PlatformClient console = PlatformClient.builder().baseUrl(fake.baseUrl()).bearerToken(fake.demo().compute().token()).build();
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
        assertThat(events.get("items").get(1).get("jobId").asText()).isEqualTo(create.jobId());

        JsonNode listed = body(dana.get().uri("/v1/resources?type=vm&name=" + name).exchange().expectStatus().isOk());
        assertThat(listed.get("items")).hasSize(1);
        assertThat(listed.get("items").get(0).get("wfrn").asText()).isEqualTo(wfrn);

        ObjectNode tag = json.createObjectNode().put("expectedVersion", activeVersion);
        tag.putObject("set").put("env", "dev").put("owner-team", "web");
        JsonNode tagged = body(dana.post().uri(resource(wfrn, ":tag")).bodyValue(tag).exchange().expectStatus().isOk());
        assertThat(tagged.get("tags").get("owner-team").asText()).isEqualTo("web");
        JsonNode byTag = body(dana.get().uri("/v1/resources?tag.owner-team=web&type=vm").exchange().expectStatus().isOk());
        assertThat(byTag.get("items")).extracting(r -> r.get("wfrn").asText()).contains(wfrn);

        ObjectNode observation = json.createObjectNode().put("status", "found").put("provider", "vsphere").put("action", "vm.get")
                .put("observedAt", java.time.Instant.now().toString());
        observation.putObject("observed").put("name", name).put("powerState", "poweredOn");
        JsonNode observed = body(asService("console-drift-service").post().uri(uri("/v1/wfrn/" + encode(wfrn) + ":record-observation?accountId=" + ACCOUNT))
                .bodyValue(observation).exchange().expectStatus().isOk());
        assertThat(observed.get("observedStatus").asText()).isEqualTo("found");
        assertThat(observed.get("observed").get("powerState").asText()).isEqualTo("poweredOn");

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
        // the delete job's start is recorded like any job's: the contract has one JobData event for both
        assertThat(later).containsExactly("Tagged", "Observed", "DeleteRequested", "ProvisioningStarted", "Deleted");
        assertThat(body(dana.get().uri("/v1/resources?type=vm&name=" + name).exchange().expectStatus().isOk()).get("items")).isEmpty();
        assertThat(body(dana.get().uri("/v1/resources?type=vm&name=" + name + "&state=deleted").exchange().expectStatus().isOk()).get("items")).hasSize(1);
        dana.post().uri(resource(wfrn, ":tag")).bodyValue(tag).exchange().expectStatus().isEqualTo(410);
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
}
