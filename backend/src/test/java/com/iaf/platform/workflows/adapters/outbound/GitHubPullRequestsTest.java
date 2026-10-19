package com.iaf.platform.workflows.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.platform.common.domain.ConflictException;
import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.testing.LocalHttp;
import com.iaf.platform.workflows.SampleFixtures;
import com.iaf.platform.workflows.domain.ChangeRequest;
import com.iaf.platform.workflows.domain.ChangeRequest.Check;
import com.iaf.platform.workflows.domain.WorkflowMermaid;
import com.iaf.platform.workflows.domain.WorkflowYaml;
import com.sun.net.httpserver.HttpServer;

import reactor.core.publisher.Mono;

/** The GitHub REST calls, against a local stand-in for api.github.com. */
class GitHubPullRequestsTest {

    static final String TOKEN = "ghp_not_a_real_token";
    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    static final ObjectMapper JSON = new ObjectMapper();
    static final String YAML = "apiVersion: iaf.platform/v1\nkind: Workflow\nmetadata: { name: vm-restart, version: 1.3.0 }\n";
    static final String FILE = "/repos/acme/content/contents/workflows/team-compute/vm-restart.yaml";

    record Seen(String method, String path, String authorization, String body) {
        JsonNode json() {
            try {
                return JSON.readTree(body);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    record Canned(int status, String body) {
    }

    final List<Seen> seen = new ArrayList<>();
    final Map<String, List<Canned>> answers = new LinkedHashMap<>();
    HttpServer github;
    PlatformProperties props;
    CredentialsPort credentials;
    GitHubPullRequests client;

    void answer(String methodAndPath, int status, String body) {
        answers.computeIfAbsent(methodAndPath, k -> new ArrayList<>()).add(new Canned(status, body));
    }

    @BeforeEach
    void start() throws IOException {
        github = LocalHttp.start(exchange -> {
            String path = exchange.getRequestURI().getRawPath() + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery());
            String key = exchange.getRequestMethod() + " " + path;
            synchronized (seen) {
                seen.add(new Seen(exchange.getRequestMethod(), path, exchange.getRequestHeaders().getFirst("Authorization"), LocalHttp.body(exchange)));
            }
            List<Canned> list = answers.get(key);
            if (list == null || list.isEmpty()) {
                LocalHttp.respond(exchange, 404, "{\"message\":\"Not Found\"}");
                return;
            }
            Canned c = list.size() > 1 ? list.remove(0) : list.get(0);
            LocalHttp.respond(exchange, c.status(), c.body());
        });
        props = new PlatformProperties();
        props.getGithub().setApiUrl(LocalHttp.url(github));
        props.getGithub().setRepo("acme/content");
        props.getGithub().setTimeout(Duration.ofSeconds(5));
        credentials = mock(CredentialsPort.class);
        when(credentials.resolve(anyString())).thenReturn(Mono.empty());
        when(credentials.resolve("vault:github/platform-token")).thenReturn(Mono.just(TOKEN));
        client = new GitHubPullRequests(props, credentials, JSON);
    }

    @AfterEach
    void stop() {
        github.stop(0);
    }

    static ChangeRequest changeRequest() {
        String diff = "--- a/workflows/team-compute/vm-restart.yaml\n+++ b/workflows/team-compute/vm-restart.yaml\n@@ -1 +1 @@\n-x\n+y\n";
        return new ChangeRequest("cr_9", ChangeRequest.Kind.WORKFLOW, "vm-restart", "1.3.0", "change/vm-restart-1.3.0", "Update Restart a VM 1.3.0", "ana",
                ChangeRequest.Status.OPEN, List.of(new Check("schema", "Schema", "passed", "valid"), new Check("policy", "Policy", "failed", "change freeze")),
                "canary", YAML, diff, NOW, null, null, null);
    }

    void happyPath() {
        answer("GET /repos/acme/content/git/ref/heads/main", 200, "{\"object\":{\"sha\":\"base000\"}}");
        answer("POST /repos/acme/content/git/refs", 201, "{\"ref\":\"refs/heads/change/vm-restart-1.3.0\"}");
        answer("PUT " + FILE, 201, "{\"commit\":{\"sha\":\"c0ffee1\"}}");
        answer("POST /repos/acme/content/pulls", 201, "{\"number\":42,\"html_url\":\"https://github.com/acme/content/pull/42\"}");
        answer("POST /repos/acme/content/statuses/c0ffee1", 201, "{}");
    }

    @Test
    void aChangeRequestBecomesABranchACommitAPullRequestAndOneStatusPerCheck() {
        happyPath();

        GitHubPullRequests.PullRequest pr = client.open(changeRequest());

        assertThat(pr.number()).isEqualTo(42);
        assertThat(pr.url()).isEqualTo("https://github.com/acme/content/pull/42");
        assertThat(pr.commit()).isEqualTo("c0ffee1");
        assertThat(seen).extracting(s -> s.method() + " " + s.path()).containsExactly(
                "GET /repos/acme/content/git/ref/heads/main",
                "POST /repos/acme/content/git/refs",
                "GET " + FILE + "?ref=change%2Fvm-restart-1.3.0",
                "PUT " + FILE,
                "POST /repos/acme/content/pulls",
                "POST /repos/acme/content/statuses/c0ffee1",
                "POST /repos/acme/content/statuses/c0ffee1");
        assertThat(seen).allSatisfy(s -> assertThat(s.authorization()).isEqualTo("Bearer " + TOKEN));
        assertThat(seen.get(1).json().path("ref").asText()).isEqualTo("refs/heads/change/vm-restart-1.3.0");
        assertThat(seen.get(1).json().path("sha").asText()).isEqualTo("base000");
        JsonNode file = seen.get(3).json();
        assertThat(new String(Base64.getDecoder().decode(file.path("content").asText()), StandardCharsets.UTF_8)).isEqualTo(YAML);
        assertThat(file.path("branch").asText()).isEqualTo("change/vm-restart-1.3.0");
        assertThat(file.has("sha")).as("a new file").isFalse();
        JsonNode pull = seen.get(4).json();
        assertThat(pull.path("head").asText()).isEqualTo("change/vm-restart-1.3.0");
        assertThat(pull.path("base").asText()).isEqualTo("main");
        assertThat(pull.path("title").asText()).isEqualTo("Update Restart a VM 1.3.0");
        assertThat(pull.path("body").asText()).contains("cr_9").contains("Rollout: canary").contains("| Policy | failed | change freeze |");
        assertThat(seen.get(5).json().path("context").asText()).isEqualTo("iaf/schema");
        assertThat(seen.get(5).json().path("state").asText()).isEqualTo("success");
        assertThat(seen.get(6).json().path("context").asText()).isEqualTo("iaf/policy");
        assertThat(seen.get(6).json().path("state").asText()).isEqualTo("failure");
        assertThat(seen.get(6).json().path("description").asText()).isEqualTo("failed: change freeze");
    }

    @Test
    void aBranchLeftFromAnEarlierChangeIsResetAndAPullRequestAlreadyOpenIsUsed() {
        answer("GET /repos/acme/content/git/ref/heads/main", 200, "{\"object\":{\"sha\":\"base000\"}}");
        answer("POST /repos/acme/content/git/refs", 422, "{\"message\":\"Reference already exists\"}");
        answer("PATCH /repos/acme/content/git/refs/heads/change/vm-restart-1.3.0", 200, "{}");
        answer("GET " + FILE + "?ref=change%2Fvm-restart-1.3.0", 200, "{\"sha\":\"blob777\"}");
        answer("PUT " + FILE, 200, "{\"commit\":{\"sha\":\"c0ffee2\"}}");
        answer("POST /repos/acme/content/pulls", 422, "{\"message\":\"A pull request already exists for acme:change/vm-restart-1.3.0.\"}");
        answer("GET /repos/acme/content/pulls?state=open&head=acme%3Achange%2Fvm-restart-1.3.0", 200,
                "[{\"number\":7,\"html_url\":\"https://github.com/acme/content/pull/7\"}]");
        answer("POST /repos/acme/content/statuses/c0ffee2", 201, "{}");

        GitHubPullRequests.PullRequest pr = client.open(changeRequest());

        assertThat(pr.number()).isEqualTo(7);
        Seen reset = seen.stream().filter(s -> s.method().equals("PATCH")).findFirst().orElseThrow();
        assertThat(reset.json().path("sha").asText()).isEqualTo("base000");
        assertThat(reset.json().path("force").asBoolean()).isTrue();
        Seen put = seen.stream().filter(s -> s.method().equals("PUT")).findFirst().orElseThrow();
        assertThat(put.json().path("sha").asText()).as("the file on the branch is replaced").isEqualTo("blob777");
    }

    @Test
    void mergingAndClosingActOnThePullRequest() {
        answer("GET /repos/acme/content/pulls/42", 200, "{\"state\":\"open\",\"merged\":false}");
        answer("PUT /repos/acme/content/pulls/42/merge", 200, "{\"merged\":true}");
        answer("PATCH /repos/acme/content/pulls/43", 200, "{\"state\":\"closed\"}");

        assertThat(client.state(42)).isEqualTo(new GitHubPullRequests.State(true, false));
        client.merge(42, "Update Restart a VM 1.3.0");
        client.close(43);

        Seen merge = seen.stream().filter(s -> s.path().endsWith("/merge")).findFirst().orElseThrow();
        assertThat(merge.json().path("merge_method").asText()).isEqualTo("squash");
        assertThat(merge.json().path("commit_title").asText()).isEqualTo("Update Restart a VM 1.3.0 (#42)");
        assertThat(seen.get(2).json().path("state").asText()).isEqualTo("closed");
    }

    @Test
    void aPendingCheckIsAPendingStatusAndAChangedCheckIsPostedAgainOnTheHeadCommit() {
        answer("GET /repos/acme/content/pulls/42", 200, "{\"state\":\"open\",\"head\":{\"sha\":\"c0ffee9\"}}");
        answer("POST /repos/acme/content/statuses/c0ffee9", 201, "{}");

        client.statuses(42, List.of(new Check("requires", "Requires", "pending", "waiting for cr_10 (provider ticketing 0.1.0) to merge")));
        client.statuses(42, List.of(new Check("requires", "Requires", "passed", "published: cr_10 (provider ticketing 0.1.0)"),
                new Check("lint", "Lint", "skipped", null)));
        client.statuses(42, List.of(new Check("requires", "Requires", "failed", "cr_10 (provider ticketing 0.1.0) was closed without merging")));

        List<JsonNode> posted = seen.stream().filter(s -> s.method().equals("POST")).map(Seen::json).toList();
        assertThat(posted).extracting(n -> n.path("context").asText(), n -> n.path("state").asText()).containsExactly(
                tuple("iaf/requires", "pending"), tuple("iaf/requires", "success"), tuple("iaf/lint", "success"), tuple("iaf/requires", "failure"));
        assertThat(posted.get(0).path("description").asText()).isEqualTo("pending: waiting for cr_10 (provider ticketing 0.1.0) to merge");
        assertThat(seen).filteredOn(s -> s.method().equals("POST")).extracting(Seen::path).containsOnly("/repos/acme/content/statuses/c0ffee9");

        answer("GET /repos/acme/content/pulls/43", 200, "{\"state\":\"open\"}");
        assertThatThrownBy(() -> client.statuses(43, List.of(new Check("requires", "Requires", "passed", null))))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getReason()).isEqualTo("GitHub answered pull request #43 without its head commit"));
    }

    @Test
    void aMergeGitHubRefusesIsAConflictAndAnOutageIsABadGatewayThatNeverShowsTheToken() {
        answer("PUT /repos/acme/content/pulls/42/merge", 405, "{\"message\":\"Required status check \\\"iaf/policy\\\" is failing.\"}");
        assertThatThrownBy(() -> client.merge(42, "t"))
                .isInstanceOf(ConflictException.class)
                .hasMessage("GitHub did not merge pull request #42: Required status check \"iaf/policy\" is failing.");

        answer("GET /repos/acme/content/git/ref/heads/main", 500, "{\"message\":\"Server Error\"}");
        assertThatThrownBy(() -> client.open(changeRequest()))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(e.getReason()).isEqualTo("Could not read the base branch main on GitHub: 500 Server Error");
                    assertThat(e.getReason()).doesNotContain(TOKEN);
                });
    }

    @Test
    void theTokenIsAVaultReferenceThatMustResolve() {
        props.getGithub().setToken("ghp_plain_in_the_config");
        assertThatThrownBy(() -> client.state(1)).isInstanceOf(IllegalStateException.class).hasMessageContaining("vault: reference");

        props.getGithub().setToken("vault:github/missing");
        assertThatThrownBy(() -> client.state(1)).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getReason()).isEqualTo("No GitHub token at vault:github/missing"));

        props.getGithub().setToken("vault:github/platform-token");
        props.getGithub().setRepo("not a repo");
        assertThatThrownBy(() -> client.state(1)).isInstanceOf(IllegalStateException.class).hasMessageContaining("owner/name");
        assertThat(seen).isEmpty();
    }

    @Test
    void thePullRequestNumberIsTheLastSegmentOfItsAddress() {
        assertThat(GitHubPullRequests.number("https://github.com/acme/content/pull/42")).isEqualTo(42);
        assertThat(GitHubPullRequests.number("https://github.com/acme/content/pull/")).isEqualTo(-1);
        assertThat(GitHubPullRequests.number(null)).isEqualTo(-1);
    }

    static ChangeRequest writing(String yaml) {
        ChangeRequest cr = changeRequest();
        return new ChangeRequest(cr.id(), cr.kind(), cr.name(), cr.version(), cr.branch(), cr.title(), cr.author(), cr.status(), cr.checks(), cr.strategy(),
                yaml, cr.diff(), NOW, null, null, null);
    }

    @Test
    void theBodyDrawsTheWorkflowAsAMermaidFlowchartAfterTheChecks() {
        String yaml = SampleFixtures.yaml("workflows/team-compute/vm-restart.yaml");

        String body = GitHubPullRequests.body(writing(yaml), "workflows/team-compute/vm-restart.yaml");

        assertThat(body).contains("\n```mermaid\n" + WorkflowMermaid.of(WorkflowYaml.parse(yaml)) + "```\n")
                .contains("\n    s1[\"Check VM exists<br/>provider: vsphere vm.get\"]\n")
                .endsWith("```\n\nMerging here publishes the version on the platform; closing it closes the change request.\n");
        assertThat(body.indexOf("| Policy | failed | change freeze |")).isLessThan(body.indexOf("```mermaid"));

        ChangeRequest provider = new ChangeRequest("cr_p", ChangeRequest.Kind.PROVIDER, "fleet", "1.1.0", "change/provider-fleet-1.1.0", "t", "ana",
                ChangeRequest.Status.OPEN, List.of(), "canary", "apiVersion: iaf.platform/v1\nkind: Provider\n", "", NOW, null, null, null);
        assertThat(GitHubPullRequests.body(provider, "providers/fleet.yaml")).doesNotContain("mermaid");
        assertThat(GitHubPullRequests.body(writing("metadata: ["), "workflows/x.yaml")).as("a file that does not parse").doesNotContain("mermaid");

        StringBuilder huge = new StringBuilder("apiVersion: iaf.platform/v1\nkind: Workflow\n"
                + "metadata: { name: huge, title: Huge, noun: huge, verb: run, owner: team-platform, version: 1.0.0 }\nworkflow:\n  do:\n");
        for (int i = 0; i < 1200; i++) {
            huge.append("    - wait").append(i).append(": { name: Wait a little while number ").append(i).append(", wait: PT1S }\n");
        }
        assertThat(WorkflowMermaid.of(WorkflowYaml.parse(huge.toString()))).hasSizeGreaterThan(GitHubPullRequests.MERMAID_TEXT);
        assertThat(GitHubPullRequests.body(writing(huge.toString()), "workflows/huge.yaml")).doesNotContain("mermaid")
                .contains("The workflow is too large to draw here.");
    }

    @Test
    void theFileIsTheOneTheDiffNamesElseTheKindsFolder() {
        assertThat(GitHubPullRequests.pathOf(changeRequest())).isEqualTo("workflows/team-compute/vm-restart.yaml");
        ChangeRequest provider = new ChangeRequest("cr_p", ChangeRequest.Kind.PROVIDER, "fleet", "1.1.0", "change/provider-fleet-1.1.0", "t", "ana",
                ChangeRequest.Status.OPEN, List.of(), "canary", "kind: Provider", "", NOW, null, null, null);
        assertThat(GitHubPullRequests.pathOf(provider)).isEqualTo("providers/fleet.yaml");
    }
}
