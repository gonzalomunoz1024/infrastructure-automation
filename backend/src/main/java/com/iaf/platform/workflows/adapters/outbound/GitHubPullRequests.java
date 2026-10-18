package com.iaf.platform.workflows.adapters.outbound;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.domain.ConflictException;
import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.workflows.domain.ChangeRequest;
import com.iaf.platform.workflows.domain.ChangeRequest.Check;
import com.iaf.platform.workflows.domain.WorkflowMermaid;

import lombok.extern.slf4j.Slf4j;

/**
 * The GitHub REST calls behind {@link GitHubChangeRequests}: a change request becomes a branch
 * {@code change/...} from the base branch with its one file committed, a pull request, and one
 * commit status per check ({@code iaf/<check>}); merging and closing act on that pull request.
 * The token is a {@code vault:} reference resolved per call; it is never logged or returned, and
 * an error names the call and GitHub's message only.
 */
@Slf4j
@Component
@Profile("github")
public class GitHubPullRequests {

    /** GitHub cuts a commit status description at 140 characters. */
    static final int STATUS_DESCRIPTION = 140;
    static final String STATUS_CONTEXT = "iaf/";
    /** Mermaid draws no diagram longer than this (its default {@code maxTextSize}). */
    static final int MERMAID_TEXT = 50_000;

    /** The pull request a change request opened. */
    public record PullRequest(int number, String url, String commit) {
    }

    /** Where a pull request stands on GitHub. */
    public record State(boolean open, boolean merged) {
    }

    private final PlatformProperties.Github props;
    private final CredentialsPort credentials;
    private final ObjectMapper json;
    private final HttpClient http;

    public GitHubPullRequests(PlatformProperties props, CredentialsPort credentials, ObjectMapper json) {
        this.props = props.getGithub();
        this.credentials = credentials;
        this.json = json;
        this.http = HttpClient.newBuilder()
                .connectTimeout(this.props.getTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** Branch, commit, pull request and statuses for an open change request. */
    public PullRequest open(ChangeRequest cr) {
        String repo = repo();
        String path = pathOf(cr);
        String base = props.getBaseBranch();
        String token = token();
        String baseSha = call(token, "GET", "/repos/" + repo + "/git/ref/heads/" + base, null, "read the base branch " + base)
                .path("object").path("sha").asText();
        branch(token, repo, cr.branch(), baseSha);
        String existing = fileSha(token, repo, path, cr.branch());
        ObjectNode file = json.createObjectNode()
                .put("message", cr.title())
                .put("content", Base64.getEncoder().encodeToString((cr.yaml() == null ? "" : cr.yaml()).getBytes(StandardCharsets.UTF_8)))
                .put("branch", cr.branch());
        if (existing != null) {
            file.put("sha", existing);
        }
        String commit = call(token, "PUT", "/repos/" + repo + "/contents/" + encodePath(path), file, "commit " + path)
                .path("commit").path("sha").asText();
        ObjectNode pull = json.createObjectNode()
                .put("title", cr.title())
                .put("head", cr.branch())
                .put("base", base)
                .put("body", body(cr, path));
        JsonNode pr;
        Response created = send(token, "POST", "/repos/" + repo + "/pulls", pull);
        if (created.status() == 422) {
            // a pull request for this branch is already open (an earlier attempt): use it
            String owner = repo.substring(0, repo.indexOf('/'));
            JsonNode open = call(token, "GET", "/repos/" + repo + "/pulls?state=open&head=" + encode(owner + ":" + cr.branch()), null,
                    "find the pull request of " + cr.branch());
            if (!open.isArray() || open.isEmpty()) {
                throw failure(created, "open the pull request for " + cr.branch());
            }
            pr = open.get(0);
        } else {
            pr = expect(created, "open the pull request for " + cr.branch());
        }
        PullRequest out = new PullRequest(pr.path("number").asInt(), pr.path("html_url").asText(), commit);
        statuses(token, repo, commit, cr.checks());
        log.info("change request {} is pull request #{} on {}", cr.id(), out.number(), repo);
        return out;
    }

    public State state(int number) {
        JsonNode pr = call(token(), "GET", "/repos/" + repo() + "/pulls/" + number, null, "read pull request #" + number);
        return new State("open".equals(pr.path("state").asText()), pr.path("merged").asBoolean(false));
    }

    /** Merges the pull request; GitHub refusing it (reviews or required checks missing, a conflict) is a 409. */
    public void merge(int number, String title) {
        ObjectNode body = json.createObjectNode().put("merge_method", props.getMergeMethod()).put("commit_title", title + " (#" + number + ")");
        Response r = send(token(), "PUT", "/repos/" + repo() + "/pulls/" + number + "/merge", body);
        if (r.status() == 405 || r.status() == 409 || r.status() == 422) {
            throw new ConflictException("GitHub did not merge pull request #" + number + ": " + message(r));
        }
        expect(r, "merge pull request #" + number);
    }

    public void close(int number) {
        call(token(), "PATCH", "/repos/" + repo() + "/pulls/" + number, json.createObjectNode().put("state", "closed"),
                "close pull request #" + number);
    }

    /** The pull request number at the end of its web address, or -1. */
    public static int number(String url) {
        if (url == null) {
            return -1;
        }
        int slash = url.lastIndexOf('/');
        try {
            return slash < 0 ? -1 : Integer.parseInt(url.substring(slash + 1).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static String pathOf(ChangeRequest cr) {
        String path = cr.path();
        if (path != null) {
            return path;
        }
        return (cr.kind() == ChangeRequest.Kind.PROVIDER ? "providers/" : "workflows/") + cr.name() + ".yaml";
    }

    static String body(ChangeRequest cr, String path) {
        StringBuilder sb = new StringBuilder();
        sb.append("Change request `").append(cr.id()).append("` from the platform: ").append(cr.kind().json()).append(' ')
                .append(cr.name()).append(' ').append(cr.version()).append(" (`").append(path).append("`).\n\n");
        sb.append("Rollout: ").append(cr.strategy() == null ? "canary" : cr.strategy()).append("\n\n");
        sb.append("| Check | Status | Detail |\n|---|---|---|\n");
        for (Check c : cr.checks() == null ? List.<Check>of() : cr.checks()) {
            sb.append("| ").append(cell(c.label() == null ? c.id() : c.label())).append(" | ").append(cell(c.status())).append(" | ")
                    .append(cell(c.detail())).append(" |\n");
        }
        WorkflowMermaid.preview(cr).ifPresent(chart -> sb.append(chart.length() <= MERMAID_TEXT
                ? "\nThe workflow, step by step:\n\n```mermaid\n" + chart + "```\n"
                : "\nThe workflow is too large to draw here.\n"));
        sb.append("\nMerging here publishes the version on the platform; closing it closes the change request.\n");
        return sb.toString();
    }

    private static String cell(String text) {
        return text == null ? "" : text.replace("|", "\\|").replace("\n", " ");
    }

    private void branch(String token, String repo, String branch, String sha) {
        Response r = send(token, "POST", "/repos/" + repo + "/git/refs", json.createObjectNode().put("ref", "refs/heads/" + branch).put("sha", sha));
        if (r.status() == 422) {
            // the branch is left from an earlier change request of the same version: start it over from the base
            call(token, "PATCH", "/repos/" + repo + "/git/refs/heads/" + branch, json.createObjectNode().put("sha", sha).put("force", true),
                    "reset the branch " + branch);
            return;
        }
        expect(r, "create the branch " + branch);
    }

    private String fileSha(String token, String repo, String path, String branch) {
        Response r = send(token, "GET", "/repos/" + repo + "/contents/" + encodePath(path) + "?ref=" + encode(branch), null);
        if (r.status() == 404) {
            return null;
        }
        return expect(r, "read " + path).path("sha").asText(null);
    }

    private void statuses(String token, String repo, String commit, List<Check> checks) {
        for (Check c : checks == null ? List.<Check>of() : checks) {
            String state = "failed".equals(c.status()) ? "failure" : "success";
            String description = (c.status() == null ? "" : c.status() + ": ") + (c.detail() == null ? "" : c.detail());
            if (description.length() > STATUS_DESCRIPTION) {
                description = description.substring(0, STATUS_DESCRIPTION - 1) + "…";
            }
            ObjectNode status = json.createObjectNode().put("state", state).put("context", STATUS_CONTEXT + c.id()).put("description", description);
            Response r = send(token, "POST", "/repos/" + repo + "/statuses/" + commit, status);
            if (r.status() / 100 != 2) {
                // the pull request stands without the status; the platform's own checks still guard the merge
                log.warn("GitHub did not take status {} on {}: {} {}", c.id(), commit, r.status(), message(r));
            }
        }
    }

    // ---------------------------------------------------------------- HTTP

    private record Response(int status, String body) {
    }

    private String repo() {
        String repo = props.getRepo() == null ? "" : props.getRepo().trim();
        if (!repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
            throw new IllegalStateException("platform.github.repo must name the content repository as owner/name");
        }
        return repo;
    }

    private String token() {
        String reference = props.getToken();
        if (reference == null || !reference.startsWith("vault:")) {
            throw new IllegalStateException("platform.github.token must be a vault: reference to the platform's GitHub token");
        }
        String token = credentials.resolve(reference).block(props.getTimeout());
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "No GitHub token at " + reference);
        }
        return token;
    }

    private JsonNode call(String token, String method, String path, JsonNode body, String what) {
        return expect(send(token, method, path, body), what);
    }

    private JsonNode expect(Response r, String what) {
        if (r.status() / 100 != 2) {
            throw failure(r, what);
        }
        try {
            return r.body() == null || r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub answered " + what + " with something that is not JSON");
        }
    }

    private ResponseStatusException failure(Response r, String what) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not " + what + " on GitHub: " + r.status() + " " + message(r));
    }

    private String message(Response r) {
        try {
            JsonNode node = json.readTree(r.body() == null ? "" : r.body());
            String message = node == null ? null : node.path("message").asText(null);
            return message == null ? "" : message;
        } catch (IOException e) {
            return "";
        }
    }

    private Response send(String token, String method, String path, JsonNode body) {
        String base = props.getApiUrl().endsWith("/") ? props.getApiUrl().substring(0, props.getApiUrl().length() - 1) : props.getApiUrl();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(props.getTimeout())
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "iaf-platform");
        HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.noBody();
        if (body != null) {
            request.header("Content-Type", "application/json");
            publisher = HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8);
        }
        request.method(method.toUpperCase(Locale.ROOT), publisher);
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(response.statusCode(), response.body());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub did not answer " + method + " " + path.split("\\?")[0] + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Interrupted while calling GitHub");
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Each segment of a repository path, encoded; the slashes stay. */
    private static String encodePath(String path) {
        StringBuilder sb = new StringBuilder();
        for (String segment : path.split("/")) {
            if (!sb.isEmpty()) {
                sb.append('/');
            }
            sb.append(encode(segment).replace("+", "%20"));
        }
        return sb.toString();
    }
}
