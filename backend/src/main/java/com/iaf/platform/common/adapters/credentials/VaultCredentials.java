package com.iaf.platform.common.adapters.credentials;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.config.PlatformProperties;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * HashiCorp Vault KV v2 over HTTP: {@code GET {vaultUrl}/v1/{mount}/data/{path}} with
 * {@code X-Vault-Token}, reading {@code data.data.value} (or the single field of the secret),
 * cached for {@value #CACHE_SECONDS} seconds. {@code store} writes the type, the author and the
 * owner team into the secret's custom metadata first (a token that cannot write metadata stores
 * nothing, so a credential never loses its owner), then {@code { value, type }}; {@code list}
 * walks the mount's metadata tree and {@code info} reads one secret's metadata, owner included.
 */
@Slf4j
@Component
@Profile("vault")
@Primary
public class VaultCredentials implements CredentialsPort {

    static final long CACHE_SECONDS = 60;
    static final Duration TIMEOUT = Duration.ofSeconds(5);
    static final int LIST_DEPTH = 4;
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private record Cached(String value, Instant expiresAt) {
    }

    private final WebClient client;
    private final String base;
    private final String mount;
    private final String token;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public VaultCredentials(WebClient.Builder builder, PlatformProperties props, Clock clock) {
        this.client = builder.clone().build();
        String url = props.getCredentials().getVaultUrl();
        this.base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.mount = props.getCredentials().getVaultMount();
        this.token = props.getCredentials().getVaultToken();
        this.clock = clock;
    }

    @Override
    public Mono<String> resolve(String reference) {
        if (reference == null || reference.isBlank()) {
            return Mono.empty();
        }
        String path = pathOf(reference);
        Cached c = cache.get(path);
        if (c != null && c.expiresAt().isAfter(clock.instant())) {
            return Mono.just(c.value());
        }
        return client.get().uri(base + "/v1/" + mount + "/data/" + path).header("X-Vault-Token", token)
                .retrieve().bodyToMono(JsonNode.class)
                .timeout(TIMEOUT)
                .flatMap(body -> Mono.justOrEmpty(valueOf(body)))
                .doOnNext(v -> cache.put(path, new Cached(v, clock.instant().plusSeconds(CACHE_SECONDS))))
                .onErrorResume(WebClientResponseException.NotFound.class, e -> Mono.empty());
    }

    /** {@code data.data.value}, or the only field of the secret. */
    static String valueOf(JsonNode body) {
        JsonNode data = body == null ? null : body.path("data").path("data");
        if (data == null || !data.isObject() || data.isEmpty()) {
            return null;
        }
        if (data.hasNonNull("value")) {
            return data.get("value").asText();
        }
        List<String> fields = new ArrayList<>();
        data.fieldNames().forEachRemaining(fields::add);
        fields.remove("type");
        if (fields.size() == 1) {
            return data.get(fields.get(0)).asText();
        }
        return null;
    }

    @Override
    public Mono<List<CredentialInfo>> list() {
        return keys("", 0).flatMap(this::described, 4).collectList()
                .map(list -> list.stream().sorted(Comparator.comparing(CredentialInfo::name)).toList());
    }

    /** One secret's metadata; empty when the path does not exist, an error when Vault cannot be read (never a guess about the owner). */
    @Override
    public Mono<CredentialInfo> info(String reference) {
        if (reference == null || reference.isBlank()) {
            return Mono.empty();
        }
        String path = pathOf(reference);
        return metadata(path)
                .map(body -> infoOf(path, body))
                .onErrorResume(WebClientResponseException.NotFound.class, e -> Mono.empty())
                .onErrorMap(e -> new IllegalStateException("Could not read the metadata of " + CredentialsPort.referenceOf(path) + " from Vault: " + e.getMessage(), e));
    }

    private Flux<String> keys(String prefix, int depth) {
        if (depth > LIST_DEPTH) {
            return Flux.empty();
        }
        return client.get().uri(base + "/v1/" + mount + "/metadata/" + prefix + "?list=true").header("X-Vault-Token", token)
                .retrieve().bodyToMono(JsonNode.class)
                .timeout(TIMEOUT)
                .onErrorResume(WebClientResponseException.NotFound.class, e -> Mono.empty())
                .flatMapMany(body -> Flux.fromIterable(body.path("data").path("keys")))
                .map(JsonNode::asText)
                .flatMap(key -> key.endsWith("/") ? keys(prefix + key, depth + 1) : Flux.just(prefix + key));
    }

    /** A listed secret as its metadata describes it; a type guessed from the path and no owner when the metadata cannot be read. */
    private Mono<CredentialInfo> described(String path) {
        return metadata(path)
                .map(body -> infoOf(path, body))
                .onErrorResume(e -> Mono.just(new CredentialInfo(path, InMemoryCredentials.typeOf(path, null), CredentialsPort.referenceOf(path), null, null)));
    }

    private Mono<JsonNode> metadata(String path) {
        return client.get().uri(base + "/v1/" + mount + "/metadata/" + path).header("X-Vault-Token", token)
                .retrieve().bodyToMono(JsonNode.class)
                .timeout(TIMEOUT);
    }

    /** The credential the metadata of {@code path} describes: its type, owner team and last update, never a value. */
    static CredentialInfo infoOf(String path, JsonNode body) {
        JsonNode data = body.path("data");
        JsonNode custom = data.path("custom_metadata");
        String type = custom.path("type").asText(InMemoryCredentials.typeOf(path, null));
        String owner = custom.path("owner").asText(null);
        Instant updated = parseInstant(data.path("updated_time").asText(null));
        return new CredentialInfo(path, type, CredentialsPort.referenceOf(path), owner == null || owner.isBlank() ? null : owner, updated);
    }

    @Override
    public Mono<CredentialInfo> store(String name, String type, String value, String by, String owner) {
        if (name == null || name.isBlank()) {
            return Mono.error(new IllegalArgumentException("name is required"));
        }
        if (value == null || value.isEmpty()) {
            return Mono.error(new IllegalArgumentException("value is required"));
        }
        String path = pathOf(name);
        String kind = type == null || type.isBlank() ? InMemoryCredentials.typeOf(path, null) : type;
        String team = owner == null || owner.isBlank() ? null : owner.trim();
        ObjectNode secret = F.objectNode();
        ObjectNode data = secret.putObject("data");
        data.put("value", value);
        data.put("type", kind);
        ObjectNode metadata = F.objectNode();
        ObjectNode custom = metadata.putObject("custom_metadata");
        custom.put("type", kind);
        if (by != null) {
            custom.put("by", by);
        }
        if (team != null) {
            custom.put("owner", team);
        }
        // the metadata first: a value is only written once its owner is kept
        return client.post().uri(base + "/v1/" + mount + "/metadata/" + path).header("X-Vault-Token", token)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(metadata)
                .retrieve().toBodilessEntity()
                .timeout(TIMEOUT)
                .onErrorMap(e -> new IllegalStateException("Could not write the metadata (type, author, owner) of " + CredentialsPort.referenceOf(path)
                        + " to Vault, so nothing was stored: " + e.getMessage(), e))
                .then(client.post().uri(base + "/v1/" + mount + "/data/" + path).header("X-Vault-Token", token)
                        .contentType(MediaType.APPLICATION_JSON).bodyValue(secret)
                        .retrieve().toBodilessEntity()
                        .timeout(TIMEOUT))
                .then(Mono.fromSupplier(() -> {
                    cache.remove(path);
                    return new CredentialInfo(path, kind, CredentialsPort.referenceOf(path), team, clock.instant());
                }));
    }

    static String pathOf(String reference) {
        String p = reference.startsWith("vault:") ? reference.substring("vault:".length()) : reference;
        return p.startsWith("/") ? p.substring(1) : p;
    }

    private static Instant parseInstant(String s) {
        try {
            return s == null ? null : Instant.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** For tests: how many entries the cache holds. */
    int cached() {
        return cache.size();
    }
}
