package com.iaf.console.bff.iam.adapters.inbound;

import java.util.regex.Pattern;

import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.iaf.console.bff.iam.application.OrganizationProxy;
import com.iaf.console.bff.iam.application.OrganizationProxy.Call;
import com.iaf.console.bff.session.application.CurrentSession;
import com.iaf.console.kit.errors.NotFoundException;

import reactor.core.publisher.Mono;

/**
 * {@code /console/v1/organization/...} (hub decision 34), for organization admins only: every
 * account, closing one and break-glass access to one, and the organization's guardrails, each the
 * identity service's operation of the same name, its status and body passed back as they were.
 * Anyone else gets a 404.
 */
@RestController
public class OrganizationController {

    private static final Pattern ACCOUNT_ID = Pattern.compile("^[0-9]{12}$");
    private static final Pattern GUARDRAIL_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final OrganizationProxy proxy;

    public OrganizationController(OrganizationProxy proxy) {
        this.proxy = proxy;
    }

    @GetMapping("/console/v1/organization/accounts")
    public Mono<ResponseEntity<byte[]>> accounts(ServerWebExchange exchange) {
        return forward(exchange, Call.LIST_ACCOUNTS, HttpMethod.GET, "/v1/accounts", null);
    }

    @PostMapping("/console/v1/organization/accounts/{accountId}:close")
    public Mono<ResponseEntity<byte[]>> close(@PathVariable String accountId, ServerWebExchange exchange) {
        return account(accountId).then(forward(exchange, Call.CLOSE_ACCOUNT, HttpMethod.POST, "/v1/accounts/" + accountId + ":close", accountId));
    }

    @PostMapping("/console/v1/organization/accounts/{accountId}/break-glass")
    public Mono<ResponseEntity<byte[]>> breakGlass(@PathVariable String accountId, ServerWebExchange exchange) {
        return account(accountId).then(forward(exchange, Call.BREAK_GLASS, HttpMethod.POST, "/v1/accounts/" + accountId + "/break-glass", accountId));
    }

    @GetMapping("/console/v1/organization/guardrails")
    public Mono<ResponseEntity<byte[]>> guardrails(ServerWebExchange exchange) {
        return forward(exchange, Call.LIST_GUARDRAILS, HttpMethod.GET, "/v1/guardrails", null);
    }

    @PostMapping("/console/v1/organization/guardrails")
    public Mono<ResponseEntity<byte[]>> createGuardrail(ServerWebExchange exchange) {
        return forward(exchange, Call.CREATE_GUARDRAIL, HttpMethod.POST, "/v1/guardrails", null);
    }

    @GetMapping("/console/v1/organization/guardrails/{guardrailId}")
    public Mono<ResponseEntity<byte[]>> guardrail(@PathVariable String guardrailId, ServerWebExchange exchange) {
        return guardrailId(guardrailId).then(forward(exchange, Call.GET_GUARDRAIL, HttpMethod.GET, "/v1/guardrails/" + guardrailId, guardrailId));
    }

    @PatchMapping("/console/v1/organization/guardrails/{guardrailId}")
    public Mono<ResponseEntity<byte[]>> updateGuardrail(@PathVariable String guardrailId, ServerWebExchange exchange) {
        return guardrailId(guardrailId).then(forward(exchange, Call.UPDATE_GUARDRAIL, HttpMethod.PATCH, "/v1/guardrails/" + guardrailId, guardrailId));
    }

    @DeleteMapping("/console/v1/organization/guardrails/{guardrailId}")
    public Mono<ResponseEntity<byte[]>> deleteGuardrail(@PathVariable String guardrailId, ServerWebExchange exchange) {
        return guardrailId(guardrailId).then(forward(exchange, Call.DELETE_GUARDRAIL, HttpMethod.DELETE, "/v1/guardrails/" + guardrailId, guardrailId));
    }

    private Mono<ResponseEntity<byte[]>> forward(ServerWebExchange exchange, Call call, HttpMethod method, String path, String target) {
        return CurrentSession.required(exchange).flatMap(session -> IamController.body(exchange)
                .flatMap(body -> proxy.forward(session, call, method, path, target, exchange.getRequest().getURI().getRawQuery(),
                        exchange.getRequest().getHeaders(), body)))
                .map(IamController::toEntity);
    }

    /** An account id is twelve digits; anything else names no account (and is never put in a path). */
    private static Mono<Void> account(String accountId) {
        return ACCOUNT_ID.matcher(accountId).matches() ? Mono.empty() : Mono.error(new NotFoundException("account " + accountId + " not found"));
    }

    private static Mono<Void> guardrailId(String guardrailId) {
        return GUARDRAIL_ID.matcher(guardrailId).matches() ? Mono.empty() : Mono.error(new NotFoundException("guardrail " + guardrailId + " not found"));
    }
}
