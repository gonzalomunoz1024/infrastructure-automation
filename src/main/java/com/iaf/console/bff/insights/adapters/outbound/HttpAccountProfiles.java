package com.iaf.console.bff.insights.adapters.outbound;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;

import com.iaf.console.bff.identity.application.IdentityClient;
import com.iaf.console.bff.insights.domain.AccountProfile;
import com.iaf.console.bff.insights.ports.outbound.AccountProfilesPort;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/** {@code GET /v1/accounts/{accountId}} on the identity service, as the console-internal principal. */
@Component
public class HttpAccountProfiles implements AccountProfilesPort {

    private final IdentityClient identity;

    public HttpAccountProfiles(IdentityClient identity) {
        this.identity = identity;
    }

    @Override
    public Mono<AccountProfile> findProfile(AccountScope scope) {
        return identity.asService(HttpMethod.GET).uri("/v1/accounts/{accountId}", scope.accountId())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(identity.timeout())
                .map(account -> new AccountProfile(text(account, "name"), text(account, "owningTeam"), text(account, "costCentre")))
                .onErrorResume(WebClientResponseException.class, e -> e.getStatusCode().value() == HttpStatus.NOT_FOUND.value() ? Mono.empty() : Mono.error(e));
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        return value == null || value.isBlank() ? null : value;
    }
}
