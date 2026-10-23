package com.iaf.console.drift.scorecards.adapters.engine;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.drift.drift.domain.Json;
import com.iaf.console.drift.scorecards.domain.Rule;
import com.iaf.console.drift.scorecards.domain.RulePack;
import com.iaf.console.drift.scorecards.domain.RulePacks;
import com.iaf.console.drift.scorecards.domain.Verdict;
import com.iaf.console.drift.scorecards.ports.outbound.PolicyEnginePort;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The firm's OPA, through its REST API: one Data API query per pack and resource
 * ({@code POST /v1/data/console/scorecards/<pack>} with {@code {input}}) answers every rule's
 * {@code applies} and {@code deny}. With {@code push-policies} the packs' Rego is written to OPA's
 * Policy API at start ({@code PUT /v1/policies/console-scorecards/<pack>/<rule>}); otherwise the
 * firm's bundle is expected to carry the same packages. A rule OPA does not answer could not be
 * judged; a failed call fails the whole evaluation, so the stored scores stand.
 */
public final class OpaPolicyEngine implements PolicyEnginePort {

    private static final Logger log = LoggerFactory.getLogger(OpaPolicyEngine.class);

    private final WebClient web;
    private final String baseUrl;
    private final Duration timeout;

    public OpaPolicyEngine(WebClient.Builder builder, String baseUrl, Duration timeout) {
        this.web = builder.build();
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeout = timeout;
    }

    /** Writes every rule's Rego to OPA (idempotent: the same id replaces the module). */
    public Mono<Void> pushPolicies(RulePacks packs) {
        return Flux.fromIterable(packs.allRules())
                .concatMap(rule -> web.put().uri(URI.create(baseUrl + "/v1/policies/console-scorecards/" + rule.pack() + "/" + rule.id()))
                        .contentType(MediaType.TEXT_PLAIN).bodyValue(rule.rego())
                        .retrieve().toBodilessEntity().timeout(timeout))
                .then()
                .doOnSuccess(v -> log.info("opa: {} rule policies written to {}", packs.allRules().size(), baseUrl));
    }

    @Override
    public Mono<Map<String, Verdict>> evaluate(RulePack pack, List<Rule> rules, ObjectNode input) {
        ObjectNode body = Json.object();
        body.set("input", input);
        return web.post().uri(URI.create(baseUrl + "/v1/data/" + pack.regoPath())).contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .retrieve().bodyToMono(JsonNode.class)
                .timeout(timeout)
                .map(answer -> verdicts(rules, answer.path("result")));
    }

    static Map<String, Verdict> verdicts(List<Rule> rules, JsonNode result) {
        Map<String, Verdict> out = new LinkedHashMap<>();
        for (Rule rule : rules) {
            JsonNode r = result.get(rule.id().replace('-', '_'));
            if (r == null || !r.isObject()) {
                continue;
            }
            if (!r.path("applies").asBoolean(false)) {
                out.put(rule.id(), Verdict.NOT_APPLICABLE);
                continue;
            }
            JsonNode deny = r.path("deny");
            if (!deny.isArray() || deny.isEmpty()) {
                out.put(rule.id(), Verdict.pass());
            } else {
                out.put(rule.id(), Verdict.fail(deny.get(0).asText()));
            }
        }
        return out;
    }

    @Override
    public String name() {
        return "opa";
    }
}
