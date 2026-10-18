package com.iaf.platform.workflows.adapters.outbound;

import java.nio.file.Path;

import com.iaf.platform.workflows.ports.outbound.WorkflowStorePort;

class InMemoryWorkflowStoreContractTest extends WorkflowStoreContract {

    @Override
    WorkflowStorePort newStore() {
        return InMemoryWorkflowStore.empty(CLOCK);
    }

    @Override
    WorkflowStorePort seededFrom(Path root) {
        InMemoryWorkflowStore s = new InMemoryWorkflowStore(root, CLOCK);
        s.init();
        return s;
    }

    @Override
    WorkflowStorePort reopen() {
        return null;
    }
}
