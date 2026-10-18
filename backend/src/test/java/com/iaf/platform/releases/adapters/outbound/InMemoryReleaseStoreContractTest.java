package com.iaf.platform.releases.adapters.outbound;

import com.iaf.platform.releases.ports.outbound.ReleaseStore;

class InMemoryReleaseStoreContractTest extends ReleaseStoreContract {

    @Override
    ReleaseStore newStore() {
        return new InMemoryReleaseStore();
    }

    @Override
    ReleaseStore reopen() {
        return null;
    }
}
