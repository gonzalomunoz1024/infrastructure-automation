package com.iaf.console.identity.accounts.adapters.secrets;

import java.time.Instant;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.identity.accounts.domain.PlatformSecret;
import com.iaf.console.identity.accounts.ports.outbound.SecretsPort;

import reactor.core.publisher.Mono;

/**
 * The {@code vault} profile's store: Vault's KV v2 API, one secret per account at
 * {@code <mount>/data/<prefix>/<accountId>/platform-service-account} with the fields
 * {@code serviceAccountId}, {@code token} and {@code issuedAt}. The other services read the same
 * path with their own Vault identities, so the token never travels through this service's API.
 */
public final class VaultSecrets implements SecretsPort {

    private final WebClient web;
    private final String mount;
    private final String prefix;

    public VaultSecrets(WebClient.Builder builder, String baseUrl, String token, String mount, String prefix) {
        this.web = builder.baseUrl(baseUrl).defaultHeader("X-Vault-Token", token).build();
        this.mount = mount;
        this.prefix = prefix;
    }

    @Override
    public String source() {
        return "vault";
    }

    @Override
    public String vaultPath(String accountId) {
        return prefix + "/" + accountId + "/platform-service-account";
    }

    @Override
    public Mono<Void> put(PlatformSecret secret) {
        Map<String, Object> body = Map.of("data", Map.of("serviceAccountId", secret.serviceAccountId(), "token", secret.token(), "issuedAt",
                secret.issuedAt().toString()));
        return web.post().uri("/v1/{mount}/data/{path}", mount, vaultPath(secret.accountId())).bodyValue(body).retrieve().toBodilessEntity().then();
    }

    @Override
    public Mono<PlatformSecret> find(String accountId) {
        return web.get().uri("/v1/{mount}/data/{path}", mount, vaultPath(accountId)).retrieve().bodyToMono(JsonNode.class)
                .map(node -> {
                    JsonNode data = node.path("data").path("data");
                    return new PlatformSecret(accountId, data.path("serviceAccountId").asText(null), data.path("token").asText(null),
                            data.path("issuedAt").isTextual() ? Instant.parse(data.path("issuedAt").asText()) : null);
                })
                .onErrorResume(WebClientResponseException.class, e -> e.getStatusCode() == HttpStatus.NOT_FOUND ? Mono.empty() : Mono.error(e));
    }

    @Override
    public Mono<Void> delete(String accountId) {
        return web.delete().uri("/v1/{mount}/metadata/{path}", mount, vaultPath(accountId)).retrieve().toBodilessEntity().then()
                .onErrorResume(WebClientResponseException.class, e -> e.getStatusCode() == HttpStatus.NOT_FOUND ? Mono.empty() : Mono.error(e));
    }
}
