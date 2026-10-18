package com.iaf.platform.identity.application;

import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.identity.domain.AccessRules;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * A {@code vault:} reference is sent outward (to a repository, an endpoint, a registry) only
 * when the caller may use the credential: judged by {@link AccessRules#requireUseCredential} on
 * the owner the credential store keeps, before the secret is resolved. A reference the store does
 * not know passes here and fails where it is resolved, so a refusal never says whether a
 * credential exists for another team. The playbooks slice guards ref resolution and the pinning
 * at publish with it; the providers slice keeps its own copy of the same rule.
 */
public final class CredentialAccess {

    private CredentialAccess() {
    }

    /** Completes when {@code who} may use every {@code vault:} reference given (blanks and other values are skipped); a 403 otherwise. */
    public static Mono<Void> require(CredentialsPort credentials, Identity who, String... references) {
        List<String> refs = Arrays.stream(references)
                .filter(r -> r != null && r.trim().startsWith("vault:"))
                .map(String::trim)
                .distinct()
                .toList();
        return Flux.fromIterable(refs)
                .concatMap(ref -> credentials.info(ref)
                        .flatMap(info -> Mono.<Void>fromRunnable(() -> AccessRules.requireUseCredential(who, CredentialsPort.referenceOf(info.name()), info.owner()))))
                .then();
    }

    /** {@code call}, made only once {@code who} may use every reference given. */
    public static <T> Mono<T> guarded(CredentialsPort credentials, Identity who, Supplier<Mono<T>> call, String... references) {
        return require(credentials, who, references).then(Mono.defer(call::get));
    }
}
