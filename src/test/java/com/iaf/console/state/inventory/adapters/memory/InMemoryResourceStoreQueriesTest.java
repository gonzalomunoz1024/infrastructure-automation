package com.iaf.console.state.inventory.adapters.memory;

import com.iaf.console.state.inventory.adapters.ResourceStoreQueriesContract;
import com.iaf.console.state.inventory.ports.outbound.ResourceEventStorePort;
import com.iaf.console.state.inventory.ports.outbound.ResourceProjectionPort;

/** Phase 9's store reads on the in-memory stores, fresh for each test. */
class InMemoryResourceStoreQueriesTest extends ResourceStoreQueriesContract {

    private final InMemoryResourceEventStore events = new InMemoryResourceEventStore();
    private final InMemoryResourceProjection projection = new InMemoryResourceProjection();

    @Override
    protected ResourceEventStorePort events() {
        return events;
    }

    @Override
    protected ResourceProjectionPort projection() {
        return projection;
    }
}
