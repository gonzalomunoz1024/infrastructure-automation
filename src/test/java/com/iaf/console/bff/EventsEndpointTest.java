package com.iaf.console.bff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.codec.ServerSentEvent;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.bff.platform.application.PlatformClients;
import com.iaf.console.bff.trust.application.Trust;

import reactor.core.Disposable;

/** WP 3.4: the account's live events over SSE, replayed from Last-Event-ID, and kept to the account. */
class EventsEndpointTest extends DemoApp {

    @Autowired
    PlatformClients clients;

    @Autowired
    Trust trust;

    @Test
    void aJobsEventsReachTheAccountsStreamAndReplay() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        Queue<ServerSentEvent<JsonNode>> received = new ConcurrentLinkedQueue<>();
        Disposable subscription = console.events(gonzalo, null, null).subscribe(received::add, error -> { });
        try {
            String jobId = startVmRestart(clients, trust);
            Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                    .until(() -> received.stream().anyMatch(e -> isJobUpdate(e, jobId)));
            ServerSentEvent<JsonNode> first = received.stream().filter(e -> isJobUpdate(e, jobId)).findFirst().orElseThrow();
            assertNotNull(first.id(), "every event carries its sequence number as id");
            assertEquals("job.updated", first.data().path("type").asText());
            assertEquals("job.updated", first.event());
            assertNotNull(first.data().path("at").asText(null));
            long seq = Long.parseLong(first.id());
            ServerSentEvent<JsonNode> replayed = console.events(gonzalo, String.valueOf(seq - 1), null)
                    .filter(e -> e.id() != null).next().block(Duration.ofSeconds(10));
            assertNotNull(replayed);
            assertEquals(first.id(), replayed.id(), "replay starts right after Last-Event-ID");
            assertEquals(jobId, replayed.data().path("data").path("jobId").asText());
            Console.Signed raj = console.signIn("raj");
            assertThrows(RuntimeException.class, () -> console.events(raj, "0", null)
                    .filter(e -> isJobUpdate(e, jobId)).next().block(Duration.ofSeconds(3)), "the data account never sees a compute job");
        } finally {
            subscription.dispose();
        }
    }

    @Test
    void contentChangesAreBroadcastToEveryAccount() {
        Console.Signed raj = console.signIn("raj");
        ServerSentEvent<JsonNode> changed = console.events(raj, "0", "types=types.changed")
                .filter(e -> "types.changed".equals(e.event())).next().block(Duration.ofSeconds(15));
        assertNotNull(changed, "the seed's published workflows and types reach the console as types.changed");
        assertTrue(changed.data().path("key").asText().length() > 0);
    }

    @Test
    void theStreamNeedsASession() {
        assertEquals(401, console.get("/console/v1/events", null).status());
    }

    private static boolean isJobUpdate(ServerSentEvent<JsonNode> event, String jobId) {
        return "job.updated".equals(event.event()) && event.data() != null && jobId.equals(event.data().path("data").path("jobId").asText());
    }
}
