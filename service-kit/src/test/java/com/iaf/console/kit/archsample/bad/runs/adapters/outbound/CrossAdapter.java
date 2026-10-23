package com.iaf.console.kit.archsample.bad.runs.adapters.outbound;

import com.iaf.console.kit.archsample.bad.registry.adapters.outbound.MemoryReads;

/** A sample adapter of one slice that uses an adapter of another, which the rules reject. */
public final class CrossAdapter {

    private final MemoryReads registry = new MemoryReads();

    public MemoryReads registry() {
        return registry;
    }
}
