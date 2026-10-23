package com.iaf.console.bff.platform.adapters.outbound;

import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.bff.identity.application.IdentityClient;
import com.iaf.console.bff.platform.application.PlatformProperties;
import com.iaf.console.bff.platform.domain.DemoPlatformSeed;
import com.iaf.console.bff.platform.domain.PlatformCredential;
import com.iaf.console.bff.platform.ports.outbound.PlatformCredentialsPort;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/**
 * An account's platform credential, in this order: configuration ({@code console.platform.accounts.<id>}),
 * the embedded demo platform's seed, then the identity service's
 * {@code GET /v1/accounts/{id}/platform-credentials} (the token itself when {@code source} is
 * {@code local}; a Vault path is not read here yet and fails with a clear message).
 */
@Component
public class PlatformCredentials implements PlatformCredentialsPort {

    private final PlatformProperties properties;
    private final IdentityClient identity;
    private final ObjectProvider<DemoPlatformSeed> demoSeed;

    public PlatformCredentials(PlatformProperties properties, IdentityClient identity, ObjectProvider<DemoPlatformSeed> demoSeed) {
        this.properties = properties;
        this.identity = identity;
        this.demoSeed = demoSeed;
    }

    @Override
    public Mono<PlatformCredential> findCredential(AccountScope scope) {
        PlatformProperties.AccountCredential configured = properties.accounts().get(scope.accountId());
        if (configured != null && configured.token() != null && !configured.token().isBlank()) {
            return Mono.just(new PlatformCredential(scope.accountId(), configured.serviceAccountId(), configured.token()));
        }
        Optional<PlatformCredential> seeded = Optional.ofNullable(demoSeed.getIfAvailable()).flatMap(seed -> seed.account(scope.accountId()));
        if (seeded.isPresent()) {
            return Mono.just(seeded.get());
        }
        return identity.asService(HttpMethod.GET).uri("/v1/accounts/{accountId}/platform-credentials", scope.accountId())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .onStatus(status -> status == HttpStatus.NOT_FOUND, response -> Mono.error(NotFoundException.of("account", scope.accountId())))
                .bodyToMono(JsonNode.class)
                .timeout(identity.timeout())
                .flatMap(json -> {
                    String source = json.path("source").asText("local");
                    String token = json.path("token").asText(null);
                    if ("local".equals(source) && token != null && !token.isBlank()) {
                        return Mono.just(new PlatformCredential(scope.accountId(), json.path("platformServiceAccountId").asText(null), token));
                    }
                    return Mono.error(new IllegalStateException("the platform credential of account " + scope.accountId() + " is in Vault at "
                            + json.path("vaultPath").asText("?") + "; configure console.platform.accounts." + scope.accountId() + ".token or a Vault reader"));
                });
    }

    @Override
    public Mono<PlatformCredential> findInternalCredential() {
        if (properties.internalToken() != null && !properties.internalToken().isBlank()) {
            return Mono.just(new PlatformCredential(null, "console-internal", properties.internalToken()));
        }
        DemoPlatformSeed seed = demoSeed.getIfAvailable();
        if (seed != null && seed.internal() != null) {
            return Mono.just(seed.internal());
        }
        return Mono.error(new IllegalStateException("console.platform.internal-token is not set"));
    }
}
