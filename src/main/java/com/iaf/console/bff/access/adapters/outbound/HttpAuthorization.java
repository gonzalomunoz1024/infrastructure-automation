package com.iaf.console.bff.access.adapters.outbound;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.bff.access.domain.AccessCheck;
import com.iaf.console.bff.access.domain.Decision;
import com.iaf.console.bff.access.domain.Decisions;
import com.iaf.console.bff.access.domain.Denial;
import com.iaf.console.bff.access.ports.outbound.AuthorizationPort;
import com.iaf.console.bff.identity.application.IdentityClient;
import com.iaf.console.bff.session.domain.Principal;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/**
 * {@code POST /v1/authorize} at the identity service, as the console-internal principal, up to 100
 * checks per call. A refusal keeps what the identity service said of it: the part of console IAM
 * that refused it (its decision's {@code scope}, the Denial's {@code refusedBy}), the guardrail,
 * whether it may be requested and the role to suggest; an allow keeps {@code elevated}.
 */
@Component
public class HttpAuthorization implements AuthorizationPort {

    private static final int BATCH = 100;

    private final IdentityClient identity;

    public HttpAuthorization(IdentityClient identity) {
        this.identity = identity;
    }

    @Override
    public Mono<Decisions> decide(AccountScope scope, Principal principal, List<String> groups, List<AccessCheck> checks) {
        if (checks.size() > BATCH) {
            return decide(scope, principal, groups, checks.subList(0, BATCH))
                    .zipWith(decide(scope, principal, groups, checks.subList(BATCH, checks.size())), (a, b) -> {
                        List<Decision> all = new ArrayList<>(a.items());
                        all.addAll(b.items());
                        return new Decisions(all, Math.max(a.version(), b.version()));
                    });
        }
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("userId", principal.userId());
        p.put("accountId", scope.accountId());
        p.put("groups", groups);
        body.put("principal", p);
        List<Map<String, Object>> items = new ArrayList<>();
        for (AccessCheck check : checks) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("action", check.action());
            c.put("resource", check.resource());
            c.put("context", Map.of("region", scope.region()));
            items.add(c);
        }
        body.put("checks", items);
        return identity.asService(HttpMethod.POST).uri("/v1/authorize")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(identity.timeout())
                .map(json -> parse(json, checks));
    }

    static Decisions parse(JsonNode json, List<AccessCheck> checks) {
        List<Decision> decisions = new ArrayList<>();
        JsonNode items = json.path("decisions");
        for (int i = 0; i < checks.size(); i++) {
            JsonNode d = items.path(i);
            if (d.path("allowed").asBoolean(false)) {
                decisions.add(d.path("elevated").asBoolean(false) ? Decision.allowElevated() : Decision.allow());
            } else {
                AccessCheck check = checks.get(i);
                String reason = d.path("reason").asText(null);
                decisions.add(Decision.deny(Denial.decided(check.action(), check.resource(),
                        reason == null || reason.isBlank() ? "No statement allows " + check.action() : reason,
                        d.path("role").asText(null), d.path("policyId").asText(null), d.path("statement").asText(null),
                        d.path("scope").asText(null), d.path("guardrailId").asText(null),
                        d.hasNonNull("requestable") ? d.path("requestable").asBoolean() : null, d.path("suggestedRole").asText(null))));
            }
        }
        return new Decisions(decisions, json.path("version").asLong(0));
    }
}
