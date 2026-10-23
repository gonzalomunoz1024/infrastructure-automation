package com.iaf.console.drift.drift.adapters.platform;

import java.util.Locale;

import com.iaf.console.drift.drift.ports.outbound.OnBehalfOfPort;
import com.iaf.console.fake.PlatformFake;
import com.iaf.console.fake.identity.application.OnBehalfOfVerifier;
import com.iaf.console.platform.assertion.OnBehalfOfAssertions;

import reactor.core.publisher.Mono;

/**
 * The demo's signer: the embedded fake's own demo key, with the issuer and audience the fake
 * checks, stands in for the BFF's (P-02). Never used outside the {@code demo} profile.
 */
public final class FakeOnBehalfOf implements OnBehalfOfPort {

    private final OnBehalfOfAssertions.Signer signer;

    public FakeOnBehalfOf(PlatformFake fake) {
        OnBehalfOfVerifier verifier = fake.verifier();
        if (verifier.demoKey() == null) {
            throw new IllegalStateException("the embedded fake has no demo signing key");
        }
        this.signer = new OnBehalfOfAssertions.Signer(verifier.demoKey(), verifier.issuer(), verifier.audience());
    }

    @Override
    public Mono<String> assertion(String accountId, String region, Person person) {
        return Mono.fromCallable(() -> signer.sign(new OnBehalfOfAssertions.User(person.subject(), person.name() != null ? person.name() : person.subject(),
                person.email() != null ? person.email() : person.subject().toLowerCase(Locale.ROOT) + "@example.com", person.groups()), accountId, region));
    }
}
