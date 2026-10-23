package com.iaf.console.drift.auth.adapters.identity;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.drift.auth.domain.ConsolePrincipal;
import com.iaf.console.drift.auth.ports.outbound.AuthorizationPort;

import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * The identity service's decision ({@code POST /v1/authorize}, one check), asked as this service
 * with a BFF service token for the person the call is for: their user id, account and groups. An
 * identity service that does not answer refuses the write rather than let it through.
 */
public final class IdentityAuthorization implements AuthorizationPort {

    private final WebClient web;

    public IdentityAuthorization(WebClient.Builder builder, String baseUrl, ExchangeFilterFunction credentials, Duration timeout) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.web = builder.clone().baseUrl(base).clientConnector(new ReactorClientHttpConnector(HttpClient.create().responseTimeout(timeout)))
                .defaultHeader(HttpHeaders.USER_AGENT, "console-drift-service")
                .filter(credentials)
                .filter((request, next) -> next.exchange(request).timeout(timeout))
                .build();
    }

    @Override
    public Mono<Boolean> allowed(ConsolePrincipal person, String action, String resource) {
        Map<String, Object> body = Map.of(
                "principal", Map.of("userId", person.subject(), "accountId", person.accountId(), "groups", person.groups()),
                "checks", List.of(Map.of("action", action, "resource", resource)));
        return web.post().uri("/v1/authorize").bodyValue(body).retrieve().bodyToMono(JsonNode.class)
                .map(answer -> answer.path("decisions").path(0).path("allowed").asBoolean(false));
    }
}
