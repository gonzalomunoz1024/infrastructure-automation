package com.iaf.console.state.trail.adapters.memory;

import com.iaf.console.state.trail.adapters.TrailDigestStoreContract;
import com.iaf.console.state.trail.ports.outbound.TrailDigestPort;

/** The trail store's contract on the in-memory store, fresh for each test. */
class InMemoryTrailDigestsTest extends TrailDigestStoreContract {

    private final InMemoryTrailDigests digests = new InMemoryTrailDigests();

    @Override
    protected TrailDigestPort digests() {
        return digests;
    }
}
