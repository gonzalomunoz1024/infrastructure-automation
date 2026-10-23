package com.iaf.console.state.stack.adapters.memory;

import com.iaf.console.state.stack.adapters.StackStoreContract;
import com.iaf.console.state.stack.ports.outbound.StackEventStorePort;
import com.iaf.console.state.stack.ports.outbound.StackStorePort;

class InMemoryStackStoresTest extends StackStoreContract {

    private final InMemoryStacks stacks = new InMemoryStacks();
    private final InMemoryStackEvents events = new InMemoryStackEvents();

    @Override
    protected StackStorePort stacks() {
        return stacks;
    }

    @Override
    protected StackEventStorePort events() {
        return events;
    }
}
