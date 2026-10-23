package com.iaf.console.search.index.adapters.memory;

import com.iaf.console.search.index.adapters.SearchIndexContractTest;
import com.iaf.console.search.index.ports.outbound.SearchIndexPort;

/** The in-memory twin keeps the contract. */
class InMemorySearchIndexTest extends SearchIndexContractTest {

    @Override
    protected SearchIndexPort index() {
        return new InMemorySearchIndex(clock);
    }
}
