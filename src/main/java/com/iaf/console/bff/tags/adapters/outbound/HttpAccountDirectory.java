package com.iaf.console.bff.tags.adapters.outbound;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;

import com.iaf.console.bff.identity.application.IdentityClient;
import com.iaf.console.bff.tags.ports.outbound.AccountDirectoryPort;

import reactor.core.publisher.Mono;

/** {@code GET /v1/accounts/{accountId}} on the identity service, as the console-internal principal. */
@Component
public class HttpAccountDirectory implements AccountDirectoryPort {

    private final IdentityClient identity;

    public HttpAccountDirectory(IdentityClient identity) {
        this.identity = identity;
    }

    @Override
    public Mono<String> owningTeam(String accountId) {
        return identity.asService(HttpMethod.GET).uri("/v1/accounts/{accountId}", accountId)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(identity.timeout())
                .flatMap(account -> {
                    String team = account.path("owningTeam").asText(null);
                    return team == null || team.isBlank() ? Mono.<String>empty() : Mono.just(team);
                })
                .onErrorResume(WebClientResponseException.class, e -> e.getStatusCode().value() == HttpStatus.NOT_FOUND.value() ? Mono.empty() : Mono.error(e));
    }
}
