package com.iaf.platform.providers.adapters.outbound;

import java.time.Clock;
import java.time.ZoneOffset;

import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.providers.ports.outbound.ProviderStorePort;

class InMemoryProviderStoreContractTest extends ProviderStoreContract {

    @Override
    ProviderStorePort newStore(PlatformProperties props) {
        InMemoryProviderStore store = new InMemoryProviderStore(props, Clock.fixed(NOW, ZoneOffset.UTC));
        store.seed();
        return store;
    }

    @Override
    ProviderStorePort reopen(PlatformProperties props) {
        return null;
    }
}
