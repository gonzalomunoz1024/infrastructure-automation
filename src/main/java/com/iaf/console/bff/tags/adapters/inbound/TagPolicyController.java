package com.iaf.console.bff.tags.adapters.inbound;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.iaf.console.bff.session.application.CurrentSession;
import com.iaf.console.bff.tags.application.TagPolicies;
import com.iaf.console.bff.tags.domain.TagPolicy;
import com.iaf.console.kit.errors.NotFoundException;

import reactor.core.publisher.Mono;

/**
 * {@code GET /console/v1/tag-policy}: the tags every create must name in the session's account
 * (or, with {@code account}, in another account the person belongs to, for a clone), each with the
 * value the create form pre-fills.
 */
@RestController
public class TagPolicyController {

    private final TagPolicies policies;

    public TagPolicyController(TagPolicies policies) {
        this.policies = policies;
    }

    @GetMapping(path = "/console/v1/tag-policy", produces = "application/json")
    public Mono<TagPolicy> policy(@RequestParam(name = "account", required = false) String account, ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> {
            String accountId = account == null || account.isBlank() ? session.accountId() : account;
            if (session.membership(accountId).isEmpty()) {
                return Mono.error(NotFoundException.of("account", accountId));
            }
            return policies.forAccount(accountId);
        });
    }
}
