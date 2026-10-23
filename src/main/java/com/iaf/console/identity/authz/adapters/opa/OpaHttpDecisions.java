package com.iaf.console.identity.authz.adapters.opa;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.identity.authz.application.AuthorizeRequest;
import com.iaf.console.identity.authz.application.AuthorizeResponse;
import com.iaf.console.identity.authz.domain.Decision;
import com.iaf.console.identity.authz.ports.outbound.DecisionPort;

import reactor.core.publisher.Mono;

/**
 * Decides through an OPA that polls this service's bundle (profile {@code opa}):
 * {@code POST /v1/data/console/authz} with the request as the input, reading {@code decisions}
 * and {@code version} from the result.
 */
public final class OpaHttpDecisions implements DecisionPort {

    private final WebClient web;
    private final ObjectMapper json;

    public OpaHttpDecisions(WebClient.Builder builder, String opaUrl, ObjectMapper json) {
        this.web = builder.baseUrl(opaUrl).build();
        this.json = json;
    }

    @Override
    public Mono<AuthorizeResponse> decide(AuthorizeRequest request) {
        return web.post().uri("/v1/data/console/authz").bodyValue(Map.of("input", request)).retrieve().bodyToMono(JsonNode.class)
                .map(body -> {
                    JsonNode result = body.path("result");
                    List<Decision> decisions = new ArrayList<>();
                    for (JsonNode d : result.path("decisions")) {
                        decisions.add(json.convertValue(d, Decision.class));
                    }
                    if (decisions.size() != request.checks().size()) {
                        throw new IllegalStateException("OPA answered " + decisions.size() + " decisions for " + request.checks().size() + " checks");
                    }
                    return new AuthorizeResponse(decisions, result.path("version").asLong(0));
                });
    }
}
