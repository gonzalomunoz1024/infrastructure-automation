package com.iaf.platform.workflows.adapters.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.platform.common.domain.CommandAccepted;
import com.iaf.platform.common.domain.ConflictException;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.identity.application.CurrentIdentity;
import com.iaf.platform.testing.TestWeb;
import com.iaf.platform.workflows.adapters.outbound.InMemoryChangeRequests;
import com.iaf.platform.workflows.application.WorkflowCommandHandler;
import com.iaf.platform.workflows.domain.ChangeRequest;

import reactor.core.publisher.Mono;

/** GitHub's word on a pull request, checked against the webhook secret before it changes a change request. */
class GitHubWebhookControllerTest {

    static final String SECRET = "a-webhook-secret";
    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    static final String URL = "https://github.com/acme/content/pull/42";

    final WorkflowCommandHandler commands = mock(WorkflowCommandHandler.class);
    final InMemoryChangeRequests changeRequests = new InMemoryChangeRequests();
    final CredentialsPort credentials = mock(CredentialsPort.class);
    final PlatformProperties props = new PlatformProperties();
    final AtomicReference<Identity> decidedBy = new AtomicReference<>();
    final WebTestClient client;

    GitHubWebhookControllerTest() {
        when(credentials.resolve(anyString())).thenReturn(Mono.empty());
        when(credentials.resolve("vault:github/webhook-secret")).thenReturn(Mono.just(SECRET));
        when(commands.mergeChangeRequest(anyString())).thenAnswer(i -> CurrentIdentity.current().doOnNext(decidedBy::set)
                .map(who -> CommandAccepted.of(i.getArgument(0), "merged", Map.of())));
        when(commands.closeChangeRequest(anyString())).thenAnswer(i -> CurrentIdentity.current().doOnNext(decidedBy::set)
                .map(who -> CommandAccepted.of(i.getArgument(0), "closed", null)));
        client = TestWeb.client(new GitHubWebhookController(commands, changeRequests, credentials, props, new ObjectMapper()));
    }

    static String sign(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String closed(boolean merged, String url, String branch) {
        return """
                {"action":"closed","number":42,"sender":{"login":"closer"},
                 "pull_request":{"number":42,"html_url":"%s","merged":%s,"merged_by":{"login":"octocat"},"head":{"ref":"%s"}}}
                """.formatted(url, merged, branch);
    }

    void openChangeRequest(String url) {
        changeRequests.save(new ChangeRequest("cr_9", ChangeRequest.Kind.WORKFLOW, "vm-restart", "1.3.0", "change/vm-restart-1.3.0", "t", "ana",
                ChangeRequest.Status.OPEN, List.of(), "canary", "kind: Workflow", "", NOW, null, null, url));
    }

    WebTestClient.ResponseSpec deliver(String event, String body, String signature) {
        WebTestClient.RequestBodySpec request = client.post().uri("/api/v1/change-requests:github-webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-GitHub-Event", event);
        if (signature != null) {
            request = request.header("X-Hub-Signature-256", signature);
        }
        return request.bodyValue(body).exchange();
    }

    @Test
    void aMergedPullRequestMergesItsChangeRequestAsThePersonWhoMergedIt() {
        openChangeRequest(URL);
        String body = closed(true, URL, "change/vm-restart-1.3.0");

        deliver("pull_request", body, sign(SECRET, body)).expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("merged").jsonPath("$.changeRequest").isEqualTo("cr_9");

        verify(commands).mergeChangeRequest("cr_9");
        assertThat(decidedBy.get().id()).isEqualTo("github:octocat");
        assertThat(decidedBy.get().name()).isEqualTo("octocat (GitHub)");
    }

    @Test
    void aPullRequestClosedWithoutMergingClosesTheOpenChangeRequestOfItsBranch() {
        openChangeRequest(null);
        String body = closed(false, URL, "change/vm-restart-1.3.0");

        deliver("pull_request", body, sign(SECRET, body)).expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("closed");

        verify(commands).closeChangeRequest("cr_9");
        verify(commands, never()).mergeChangeRequest(anyString());
        assertThat(decidedBy.get().id()).isEqualTo("github:closer");
    }

    @Test
    void aDeliveryWithoutTheRightSignatureChangesNothing() {
        openChangeRequest(URL);
        String body = closed(true, URL, "change/vm-restart-1.3.0");

        deliver("pull_request", body, null).expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.error").isEqualTo("The webhook signature does not match");
        deliver("pull_request", body, sign("another-secret", body)).expectStatus().isUnauthorized();
        deliver("pull_request", body, "sha256=zz").expectStatus().isUnauthorized();
        deliver("pull_request", body.replace("octocat", "mallory"), sign(SECRET, body)).expectStatus().isUnauthorized();

        verify(commands, never()).mergeChangeRequest(anyString());
    }

    @Test
    void withoutItsSecretTheWebhookTakesNoDelivery() {
        props.getGithub().setWebhookSecret("vault:github/nowhere");
        String body = closed(true, URL, "b");
        deliver("pull_request", body, sign(SECRET, body)).expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.error").isEqualTo("No webhook secret at vault:github/nowhere; deliveries cannot be verified");

        props.getGithub().setWebhookSecret(SECRET);
        deliver("pull_request", body, sign(SECRET, body)).expectStatus().isEqualTo(503);
        verify(commands, never()).mergeChangeRequest(anyString());
    }

    @Test
    void pingsOtherEventsAndPullRequestsOfNoChangeRequestChangeNothing() {
        String ping = "{\"zen\":\"Keep it logically awesome.\"}";
        deliver("ping", ping, sign(SECRET, ping)).expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("pong");
        deliver("push", ping, sign(SECRET, ping)).expectStatus().isAccepted().expectBody().jsonPath("$.status").isEqualTo("ignored");
        String opened = closed(false, URL, "b").replace("\"closed\"", "\"opened\"");
        deliver("pull_request", opened, sign(SECRET, opened)).expectStatus().isAccepted();
        String unknown = closed(true, "https://github.com/acme/content/pull/99", "elsewhere");
        deliver("pull_request", unknown, sign(SECRET, unknown)).expectStatus().isAccepted()
                .expectBody().jsonPath("$.reason").isEqualTo("no change request for pull request #42");
        verify(commands, never()).mergeChangeRequest(anyString());
        verify(commands, never()).closeChangeRequest(anyString());
    }

    @Test
    void aRepeatedDeliveryAnswersWhatWasDecided() {
        openChangeRequest(URL);
        changeRequests.save(changeRequests.get("cr_9").orElseThrow().withStatus(ChangeRequest.Status.MERGED, NOW, "github:octocat"));
        String body = closed(true, URL, "change/vm-restart-1.3.0");

        deliver("pull_request", body, sign(SECRET, body)).expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("merged");
        verify(commands, never()).mergeChangeRequest(anyString());
    }

    @Test
    void aMergeThePlatformRefusesIsAConflictForGitHubToShow() {
        openChangeRequest(URL);
        when(commands.mergeChangeRequest("cr_9")).thenReturn(Mono.error(new ConflictException("checks failed")));
        String body = closed(true, URL, "change/vm-restart-1.3.0");

        deliver("pull_request", body, sign(SECRET, body)).expectStatus().isEqualTo(409).expectBody().jsonPath("$.error").isEqualTo("checks failed");
    }

    @Test
    void theSignatureIsTheHmacOfTheBody() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        assertThat(GitHubWebhookController.signed(SECRET, body, sign(SECRET, "{}"))).isTrue();
        assertThat(GitHubWebhookController.signed(SECRET, body, sign(SECRET, "{ }"))).isFalse();
        assertThat(GitHubWebhookController.signed(SECRET, body, sign(SECRET, "{}").replace("sha256=", "sha1="))).isFalse();
        assertThat(GitHubWebhookController.signed("", body, sign(SECRET, "{}"))).isFalse();
        assertThat(GitHubWebhookController.signed(SECRET, body, null)).isFalse();
    }
}
