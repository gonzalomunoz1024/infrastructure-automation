package com.iaf.console.bff.trust.adapters.outbound;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.iaf.console.bff.trust.application.TrustProperties;
import com.iaf.console.bff.trust.ports.outbound.ClientKeysPort;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * The keys of {@code console.trust.clients.*} and {@code console.trust.kubernetes}: a key set
 * written into the settings is parsed at boot (a bad one stops the boot), one behind a URI is read
 * when first needed, kept five minutes, and read again (at most every 30 seconds) when an
 * assertion names a key it does not hold. Only the public parts are ever kept.
 */
@Component
public class ConfiguredClientKeys implements ClientKeysPort {

    private static final Logger log = LoggerFactory.getLogger(ConfiguredClientKeys.class);
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final Duration REFRESH_GAP = Duration.ofSeconds(30);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final Map<String, KeySource> clients = new HashMap<>();
    private final KeySource cluster;

    public ConfiguredClientKeys(TrustProperties properties, WebClient.Builder webClients, Clock clock) {
        WebClient plain = webClients.clone().build();
        properties.clients().forEach((id, client) -> {
            if (client.jwks() != null && !client.jwks().isBlank()) {
                clients.put(id, KeySource.fixed(parse(client.jwks(), "console.trust.clients." + id + ".jwks")));
            } else if (client.jwksUri() != null && !client.jwksUri().isBlank()) {
                clients.put(id, KeySource.remote(plain, client.jwksUri(), null, clock));
            }
        });
        TrustProperties.Kubernetes k = properties.kubernetes();
        if (!k.enabled()) {
            this.cluster = null;
        } else if (k.jwks() != null && !k.jwks().isBlank()) {
            this.cluster = KeySource.fixed(parse(k.jwks(), "console.trust.kubernetes.jwks"));
        } else if (k.jwksUri() != null && !k.jwksUri().isBlank()) {
            this.cluster = KeySource.remote(clusterClient(webClients, k.caFile()), k.jwksUri(), k.tokenFile(), clock);
        } else {
            throw new IllegalArgumentException("console.trust.kubernetes.issuer is set but neither jwks nor jwks-uri is");
        }
    }

    @Override
    public Mono<JWK> clientKey(String clientId, String kid) {
        KeySource source = clients.get(clientId);
        return source == null ? Mono.empty() : source.key(kid);
    }

    @Override
    public Mono<JWK> clusterKey(String kid) {
        return cluster == null ? Mono.empty() : cluster.key(kid);
    }

    /** A JWKS ({@code {"keys": [...]}}) or a single JWK, as JSON; only its public parts. */
    static JWKSet parse(String json, String where) {
        try {
            String text = json.trim();
            JWKSet set = text.contains("\"keys\"") ? JWKSet.parse(text) : new JWKSet(JWK.parse(text));
            if (set.getKeys().isEmpty()) {
                throw new IllegalArgumentException(where + " holds no key");
            }
            if (set.getKeys().stream().anyMatch(JWK::isPrivate)) {
                log.warn("{} holds a private key; only its public part is kept, and the private part belongs to its owner alone", where);
            }
            return set.toPublicJWKSet();
        } catch (ParseException e) {
            throw new IllegalArgumentException(where + " is not a JWK set or a JWK", e);
        }
    }

    /** The cluster's API server presents a certificate of the cluster's own CA. */
    private static WebClient clusterClient(WebClient.Builder webClients, String caFile) {
        if (caFile == null || caFile.isBlank()) {
            return webClients.clone().build();
        }
        try {
            SslContext ssl = SslContextBuilder.forClient().trustManager(new File(caFile)).build();
            HttpClient http = HttpClient.create().secure(spec -> spec.sslContext(ssl));
            return webClients.clone().clientConnector(new ReactorClientHttpConnector(http)).build();
        } catch (SSLException | IllegalArgumentException e) {
            throw new IllegalStateException("console.trust.kubernetes.ca-file " + caFile + " is not a CA certificate", e);
        }
    }

    /** A set of keys, fixed or read from a URI. */
    private static final class KeySource {

        private final JWKSet fixed;
        private final WebClient web;
        private final String uri;
        private final String tokenFile;
        private final Clock clock;
        private final AtomicReference<Mono<JWKSet>> cached = new AtomicReference<>();
        private final AtomicReference<Instant> lastFetch = new AtomicReference<>(Instant.EPOCH);

        private KeySource(JWKSet fixed, WebClient web, String uri, String tokenFile, Clock clock) {
            this.fixed = fixed;
            this.web = web;
            this.uri = uri;
            this.tokenFile = tokenFile;
            this.clock = clock;
            if (fixed == null) {
                cached.set(fetch());
            }
        }

        static KeySource fixed(JWKSet keys) {
            return new KeySource(keys, null, null, null, null);
        }

        static KeySource remote(WebClient web, String uri, String tokenFile, Clock clock) {
            return new KeySource(null, web, uri, tokenFile, clock);
        }

        Mono<JWK> key(String kid) {
            if (fixed != null) {
                return Mono.justOrEmpty(find(fixed, kid));
            }
            return cached.get().flatMap(set -> Mono.justOrEmpty(find(set, kid)))
                    .onErrorResume(e -> Mono.empty())
                    .switchIfEmpty(Mono.defer(() -> {
                        Instant now = clock.instant();
                        if (now.isBefore(lastFetch.get().plus(REFRESH_GAP))) {
                            return Mono.empty();
                        }
                        cached.set(fetch());
                        return cached.get().flatMap(set -> Mono.justOrEmpty(find(set, kid))).onErrorResume(e -> Mono.empty());
                    }));
        }

        private static JWK find(JWKSet set, String kid) {
            if (kid == null) {
                List<JWK> all = set.getKeys();
                return all.size() == 1 ? all.get(0) : null;
            }
            return set.getKeyByKeyId(kid);
        }

        private Mono<JWKSet> fetch() {
            return Mono.defer(() -> {
                lastFetch.set(clock.instant());
                WebClient.RequestHeadersSpec<?> request = web.get().uri(uri).accept(MediaType.APPLICATION_JSON);
                String bearer = bearer();
                if (bearer != null) {
                    request = request.header("Authorization", "Bearer " + bearer);
                }
                return request.retrieve().bodyToMono(String.class).timeout(TIMEOUT).map(body -> {
                    JWKSet set = parse(body, uri);
                    log.info("read {} key(s) from {}", set.getKeys().size(), uri);
                    return set;
                });
            }).doOnError(e -> log.warn("the keys at {} cannot be read: {}", uri, e.getMessage()))
                    .cache(set -> TTL, e -> Duration.ZERO, () -> Duration.ZERO);
        }

        /** The token the API server wants, read anew each time since the kubelet rotates it. */
        private String bearer() {
            if (tokenFile == null || tokenFile.isBlank()) {
                return null;
            }
            try {
                String token = Files.readString(Path.of(tokenFile)).strip();
                return token.isEmpty() ? null : token;
            } catch (IOException e) {
                log.warn("console.trust.kubernetes.token-file {} cannot be read: {}", tokenFile, e.getMessage());
                return null;
            }
        }
    }
}
