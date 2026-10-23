package com.iaf.console.bff.clients.adapters.inbound;

import java.time.Instant;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.iaf.console.bff.clients.application.ApiSessions;
import com.iaf.console.bff.clients.domain.ApiSession;
import com.iaf.console.bff.session.application.CurrentSession;
import com.iaf.console.kit.paging.Page;

import reactor.core.publisher.Mono;

/**
 * The person's CLI and SDK sign-ins (decision 31): the open ones, newest first, and signing one
 * out, which ends its refresh token at once and its access tokens within a minute. Another
 * person's is a 404.
 */
@RestController
public class ApiSessionsController {

    private final ApiSessions apiSessions;

    public ApiSessionsController(ApiSessions apiSessions) {
        this.apiSessions = apiSessions;
    }

    /** One API session as the person sees it: no token, no hash, no reason for the log. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ApiSessionBody(String id, String clientId, Instant createdAt, Instant lastUsedAt, Instant expiresAt, String accountId, String region,
            String userAgent, String ip) {

        static ApiSessionBody of(ApiSession s) {
            return new ApiSessionBody(s.id(), s.clientId(), s.createdAt(), s.lastUsedAt(), s.expiresAt(), s.accountId(), s.region(), s.userAgent(), s.ip());
        }
    }

    @GetMapping(path = "/console/v1/api-sessions", produces = "application/json")
    public Mono<Page<ApiSessionBody>> list(@RequestParam(required = false) Integer limit, @RequestParam(required = false) String after,
            ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> apiSessions.list(session, limit, after)).map(page -> page.map(ApiSessionBody::of));
    }

    @DeleteMapping(path = "/console/v1/api-sessions/{apiSessionId}")
    public Mono<ResponseEntity<Void>> revoke(@PathVariable String apiSessionId, ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> apiSessions.revoke(session, apiSessionId))
                .then(Mono.fromSupplier(() -> ResponseEntity.noContent().<Void>build()));
    }
}
