package com.iaf.console.kit.archsample.bad.registry.application;

import com.iaf.console.kit.archsample.bad.registry.adapters.outbound.MemoryReads;

/** A sample application class that builds its own adapter, which the rules reject. */
public final class LeakyService {

    private final MemoryReads reads = new MemoryReads();

    public MemoryReads reads() {
        return reads;
    }
}
