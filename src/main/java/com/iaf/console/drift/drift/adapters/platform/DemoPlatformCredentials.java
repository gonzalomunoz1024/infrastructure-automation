package com.iaf.console.drift.drift.adapters.platform;

import java.util.HashMap;
import java.util.Map;

import com.iaf.console.drift.drift.ports.outbound.PlatformCredentialsPort;
import com.iaf.console.fake.PlatformFake;
import com.iaf.console.fake.demo.DemoSeed;

import reactor.core.publisher.Mono;

/** The demo accounts' tokens, from the embedded fake's seed. */
public final class DemoPlatformCredentials implements PlatformCredentialsPort {

    private final Map<String, String> tokens = new HashMap<>();

    public DemoPlatformCredentials(PlatformFake fake) {
        DemoSeed.Result seed = fake.demo();
        if (seed != null) {
            for (DemoSeed.ConsoleAccount account : new DemoSeed.ConsoleAccount[] {seed.compute(), seed.data()}) {
                if (account != null && account.token() != null) {
                    tokens.put(account.accountId(), account.token());
                }
            }
        }
    }

    @Override
    public Mono<String> tokenFor(String accountId) {
        return Mono.justOrEmpty(tokens.get(accountId));
    }
}
