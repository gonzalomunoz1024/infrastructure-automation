package com.iaf.platform.runs.adapters.outbound;

import com.iaf.platform.runs.ports.outbound.ProjectionStore;

class InMemoryProjectionStoreContractTest extends ProjectionStoreContract {

    @Override
    ProjectionStore newStore() {
        return new InMemoryProjectionStore();
    }

    @Override
    ProjectionStore reopen() {
        return null;
    }
}
