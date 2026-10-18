package com.iaf.platform.resources.adapters.outbound;

import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.resources.ports.outbound.ResourceStorePort;

class InMemoryResourceStoreContractTest extends ResourceStoreContract {

    @Override
    ResourceStorePort newStore(PlatformProperties props) {
        InMemoryResourceStore store = new InMemoryResourceStore(props, CLOCK);
        store.seed();
        return store;
    }

    @Override
    ResourceStorePort reopen(PlatformProperties props) {
        return null;
    }
}
