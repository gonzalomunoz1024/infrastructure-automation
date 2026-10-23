package com.iaf.console.drift.journey;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.drift.drift.domain.Json;
import com.iaf.console.drift.drift.domain.OwnerGuess;
import com.iaf.console.drift.drift.domain.UnmanagedObject;
import com.iaf.console.drift.drift.ports.outbound.DiscoveryStorePort;
import com.iaf.console.kit.scope.AccountScope;

/**
 * The service's own checks on a person's writes, behind the BFF's (security review of the drift
 * service): a viewer or an auditor changes nothing, a person is always the actor of what they do,
 * and only the account an unmanaged object is for may import it.
 */
class WriteChecksTest extends DemoServiceTest {

    @Autowired
    private DiscoveryStorePort discovery;

    private WebTestClient viewer() {
        return as(ACCOUNT, "vic", "team-compute", "viewer");
    }

    private WebTestClient auditor() {
        return as(ACCOUNT, "aud", "auditors", "auditor");
    }

    @Test
    void aViewerOrAnAuditorChangesNothingAndAPersonIsAlwaysTheActor() {
        JsonNode vm = vm("shop-web-1");
        String wfrn = vm.path("wfrn").asText();
        setAtProvider(vm.path("providerId").asText(), "cpu", 12);
        String findingId = openFinding(wfrn).path("findingId").asText();

        for (String decision : List.of(":accept", ":ignore", ":revert")) {
            viewer().post().uri("/v1/findings/" + findingId + decision).contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
                    .expectStatus().isForbidden();
        }
        auditor().post().uri("/v1/findings/" + findingId + ":accept").contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
                .expectStatus().isForbidden();
        viewer().put().uri("/v1/alarms/shop-web-1-cpu").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"wfrn\":\"" + wfrn + "\",\"name\":\"cpu\",\"metric\":\"cpuPercent\",\"comparison\":\"above\",\"threshold\":90}")
                .exchange().expectStatus().isForbidden();
        assertThat(finding(findingId).path("status").asText()).isEqualTo("open");

        // the body names someone else: the person who calls is recorded
        JsonNode accepted = body(dana().post().uri("/v1/findings/" + findingId + ":accept").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"reason\":\"needed\",\"actor\":\"mallory\"}").exchange().expectStatus().isOk());
        assertThat(accepted.path("decidedBy").asText()).isEqualTo("dana");
    }

    @Test
    void onlyTheAccountAnUnmanagedObjectIsForMayImportIt() {
        AccountScope data = AccountScope.of(OTHER_ACCOUNT, "global", "test");
        Instant now = Instant.now();
        OwnerGuess compute = new OwnerGuess(ACCOUNT, "Compute", "team-compute", OwnerGuess.LOW, List.of("a test's guess"));
        discovery.saveUnmanaged(new UnmanagedObject(OTHER_ACCOUNT, "vm-7777", "vm", "global", "vm-7777", Json.object(), now, now, compute, null, null))
                .block();
        discovery.saveUnmanaged(new UnmanagedObject(OTHER_ACCOUNT, "vm-7778", "vm", "global", "vm-7778", Json.object(), now, now, null, null, null))
                .block();
        try {
            WebTestClient ana = as(OTHER_ACCOUNT, "ana", "team-data,developer");
            JsonNode notYours = body(ana.post().uri("/v1/unmanaged:import").contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"type\":\"vm\",\"region\":\"global\",\"providerId\":\"vm-7777\"}").exchange().expectStatus().isEqualTo(409));
            assertThat(notYours.path("error").asText()).startsWith("unmanaged.notYours");
            assertThat(notYours.path("detail").path("accountId").asText()).isEqualTo(ACCOUNT);
            // nobody is guessed: an operator may not claim it, only an account admin
            assertThat(body(ana.post().uri("/v1/unmanaged:import").contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"type\":\"vm\",\"region\":\"global\",\"providerId\":\"vm-7778\"}").exchange().expectStatus().isEqualTo(409))
                    .path("error").asText()).startsWith("unmanaged.notYours");
            as(OTHER_ACCOUNT, "vic", "team-data", "viewer").post().uri("/v1/unmanaged:import").contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"type\":\"vm\",\"region\":\"global\",\"providerId\":\"vm-7777\"}").exchange().expectStatus().isForbidden();
        } finally {
            discovery.deleteUnmanaged(data, "vm", "global", "vm-7777").block();
            discovery.deleteUnmanaged(data, "vm", "global", "vm-7778").block();
        }
    }
}
