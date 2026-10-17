package com.iaf.platform.common.adapters.policy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.ports.PolicyPort;
import com.iaf.platform.config.PlatformProperties;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * The firm's OPA engine over its data API: {@code POST {platform.policy.opa-url}/<phase>} with
 * {@code { input }}, reading {@code result.allow}, {@code result.needsApproval} and
 * {@code result.rules[]}. Fails closed: an unreachable engine denies.
 */
@Slf4j
@Component
@Profile("opa")
@Primary
public class OpaPolicy implements PolicyPort {

    public static final String ENGINE = "opa";
    static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private final WebClient client;
    private final String baseUrl;

    public OpaPolicy(WebClient.Builder builder, PlatformProperties props) {
        this.client = builder.clone().build();
        String url = props.getPolicy().getOpaUrl();
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @Override
    public Mono<PolicyDecision> evaluate(PolicyInput input) {
        Phase phase = input.phase() == null ? Phase.INVOKE : input.phase();
        String url = baseUrl + "/" + phase.name().toLowerCase().replace('_', '-');
        ObjectNode body = F.objectNode();
        body.set("input", toJson(input));
        return client.post().uri(url).contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .retrieve().bodyToMono(JsonNode.class)
                .timeout(TIMEOUT)
                .map(OpaPolicy::decision)
                .onErrorResume(e -> {
                    log.warn("OPA at {} failed: {}; denying", url, e.toString());
                    return Mono.just(new PolicyDecision("deny", List.of(new Rule("opa.unreachable", "deny", "The policy engine could not be reached: " + e.getMessage())), ENGINE));
                });
    }

    @Override
    public String engine() {
        return ENGINE;
    }

    static PolicyDecision decision(JsonNode response) {
        JsonNode result = response == null ? null : response.path("result");
        if (result == null || result.isMissingNode() || result.isNull()) {
            return new PolicyDecision("deny", List.of(new Rule("opa.undefined", "deny", "The policy returned no result for this phase")), ENGINE);
        }
        boolean allow = result.path("allow").asBoolean(false);
        boolean needsApproval = result.path("needsApproval").asBoolean(false) || result.path("needs_approval").asBoolean(false);
        List<Rule> rules = new ArrayList<>();
        for (JsonNode r : result.path("rules")) {
            rules.add(new Rule(r.path("id").asText("rule"), r.path("result").asText(allow ? "pass" : "deny"), r.path("message").asText(null)));
        }
        return new PolicyDecision(!allow ? "deny" : needsApproval ? "needs-approval" : "allow", rules, ENGINE);
    }

    static ObjectNode toJson(PolicyInput input) {
        ObjectNode in = F.objectNode();
        in.put("phase", input.phase() == null ? "invoke" : input.phase().name().toLowerCase().replace('_', '-'));
        Identity who = input.who();
        if (who != null) {
            ObjectNode w = in.putObject("who");
            w.put("id", who.id());
            w.put("name", who.name());
            w.put("email", who.email());
            ArrayNode teams = w.putArray("teams");
            (who.teams() == null ? List.<String>of() : who.teams()).forEach(teams::add);
            ArrayNode roles = w.putArray("roles");
            (who.roles() == null ? List.<String>of() : who.roles()).forEach(roles::add);
            w.put("admin", who.admin());
            w.put("kind", who.kind() == null ? "user" : who.kind().json());
        }
        in.put("workflow", input.workflow());
        in.put("version", input.version());
        in.set("input", input.input() == null ? F.objectNode() : input.input());
        in.set("context", input.context() == null ? F.objectNode() : input.context());
        return in;
    }
}
