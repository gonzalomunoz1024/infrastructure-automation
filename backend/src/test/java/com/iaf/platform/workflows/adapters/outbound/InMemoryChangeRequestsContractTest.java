package com.iaf.platform.workflows.adapters.outbound;

import com.iaf.platform.workflows.ports.outbound.ChangeRequestPort;

class InMemoryChangeRequestsContractTest extends ChangeRequestsContract {

    @Override
    ChangeRequestPort newStore() {
        return new InMemoryChangeRequests();
    }

    @Override
    ChangeRequestPort reopen() {
        return null;
    }
}
