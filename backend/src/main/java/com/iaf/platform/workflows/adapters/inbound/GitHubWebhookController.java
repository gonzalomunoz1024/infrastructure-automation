package com.iaf.platform.workflows.adapters.inbound;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.platform.common.domain.CommandAccepted;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.identity.application.CurrentIdentity;
import com.iaf.platform.workflows.application.WorkflowCommandHandler;
import com.iaf.platform.workflows.domain.ChangeRequest;
import com.iaf.platform.workflows.ports.outbound.ChangeRequestPort;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * {@code POST /api/v1/change-requests:github-webhook} (profile {@code github}): GitHub reports a
 * pull request merged or closed, and the platform merges or closes its change request, so a merge
 * on GitHub publishes the version as a merge in the UI does. Deliveries carry no platform
 * credential; each one must be signed with the webhook secret ({@code X-Hub-Signature-256}, an
 * HMAC-SHA256 of the body), read from the {@code vault:} reference in
 * {@code platform.github.webhook-secret}. An unsigned or wrongly signed delivery is a 401.
 *
 * <p>The decision is taken as {@code github:<login>}, the person who merged or closed it on
 * GitHub; reviews are GitHub's to require there (branch protection). The platform's own checks
 * still hold: a merge whose checks failed is a 409 and the change request stays open.
 */
@Slf4j
@RestController
@Profile("github")
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class GitHubWebhookController {

    public static final String PATH = "/api/v1/change-requests:github-webhook";
    static final String SIGNATURE_PREFIX = "sha256=";

    private final WorkflowCommandHandler commands;
    private final ChangeRequestPort changeRequests;
    private final CredentialsPort credentials;
    private final PlatformProperties props;
    private final ObjectMapper json;

    @PostMapping("/change-requests:github-webhook")
    public Mono<ResponseEntity<Map<String, Object>>> receive(@RequestHeader(value = "X-GitHub-Event", required = false) String event,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature, @RequestBody(required = false) byte[] body) {
        byte[] payload = body == null ? new byte[0] : body;
        return secret().flatMap(secret -> {
            if (!signed(secret, payload, signature)) {
                log.warn("GitHub webhook delivery refused: the signature does not match");
                return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "The webhook signature does not match"));
            }
            return handle(event, payload);
        });
    }

    private Mono<String> secret() {
        String reference = props.getGithub().getWebhookSecret();
        if (reference == null || !reference.startsWith("vault:")) {
            return Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "platform.github.webhook-secret must be a vault: reference; deliveries cannot be verified"));
        }
        return credentials.resolve(reference)
                .filter(s -> !s.isEmpty())
                .switchIfEmpty(Mono.error(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "No webhook secret at " + reference + "; deliveries cannot be verified")));
    }

    /** Whether {@code header} is {@code sha256=} and the hex HMAC-SHA256 of the body with the secret, compared in constant time. */
    static boolean signed(String secret, byte[] payload, String header) {
        if (secret == null || secret.isEmpty() || header == null || !header.startsWith(SIGNATURE_PREFIX)) {
            return false;
        }
        byte[] given;
        try {
            given = HexFormat.of().parseHex(header.substring(SIGNATURE_PREFIX.length()).trim());
        } catch (IllegalArgumentException e) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return MessageDigest.isEqual(mac.doFinal(payload), given);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    private Mono<ResponseEntity<Map<String, Object>>> handle(String event, byte[] payload) {
        if ("ping".equals(event)) {
            return Mono.just(answer(HttpStatus.OK, "pong", null, null));
        }
        if (!"pull_request".equals(event)) {
            return Mono.just(answer(HttpStatus.ACCEPTED, "ignored", null, "only pull_request events change change requests"));
        }
        JsonNode root;
        try {
            root = json.readTree(payload);
        } catch (IOException e) {
            return Mono.error(new IllegalArgumentException("The delivery is not JSON"));
        }
        JsonNode pr = root == null ? null : root.path("pull_request");
        if (pr == null || !pr.isObject()) {
            return Mono.error(new IllegalArgumentException("A pull_request delivery without its pull_request"));
        }
        if (!"closed".equals(root.path("action").asText())) {
            return Mono.just(answer(HttpStatus.ACCEPTED, "ignored", null, "only a merged or closed pull request changes a change request"));
        }
        boolean merged = pr.path("merged").asBoolean(false);
        String url = pr.path("html_url").asText(null);
        String branch = pr.path("head").path("ref").asText(null);
        int number = pr.path("number").asInt();
        String login = merged ? pr.path("merged_by").path("login").asText(null) : null;
        if (login == null) {
            login = root.path("sender").path("login").asText(null);
        }
        Identity who = person(login);
        return Mono.fromCallable(() -> find(url, branch)).subscribeOn(Schedulers.boundedElastic()).flatMap(found -> {
            if (found.isEmpty()) {
                return Mono.just(answer(HttpStatus.ACCEPTED, "ignored", null, "no change request for pull request #" + number));
            }
            ChangeRequest cr = found.get();
            if (cr.status() != ChangeRequest.Status.OPEN) {
                return Mono.just(answer(HttpStatus.OK, cr.status().json(), cr.id(), null));
            }
            Mono<CommandAccepted> decision = merged ? commands.mergeChangeRequest(cr.id()) : commands.closeChangeRequest(cr.id());
            log.info("pull request #{} {} on GitHub by {}: {} change request {}", number, merged ? "merged" : "closed", who.id(),
                    merged ? "merging" : "closing", cr.id());
            return decision.contextWrite(ctx -> CurrentIdentity.with(ctx, who))
                    .map(accepted -> answer(HttpStatus.OK, accepted.status(), cr.id(), null));
        });
    }

    /** The change request of a pull request: by its address, else the open one on its branch. */
    private Optional<ChangeRequest> find(String url, String branch) {
        List<ChangeRequest> all = changeRequests.all();
        Optional<ChangeRequest> byUrl = url == null ? Optional.empty() : all.stream().filter(cr -> url.equals(cr.url())).findFirst();
        if (byUrl.isPresent()) {
            return byUrl;
        }
        return branch == null ? Optional.empty()
                : all.stream().filter(cr -> cr.status() == ChangeRequest.Status.OPEN && branch.equals(cr.branch())).findFirst();
    }

    /** Who decided on GitHub; branch protection there required whatever review the repository asks for. */
    static Identity person(String login) {
        String name = login == null || login.isBlank() ? "github" : login.trim();
        return new Identity("github:" + name, name + " (GitHub)", null, List.of(), List.of("platform"), true, Identity.Kind.SYSTEM);
    }

    private static ResponseEntity<Map<String, Object>> answer(HttpStatus status, String outcome, String changeRequest, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", outcome);
        if (changeRequest != null) {
            body.put("changeRequest", changeRequest);
        }
        if (reason != null) {
            body.put("reason", reason);
        }
        return ResponseEntity.status(status).body(body);
    }
}
