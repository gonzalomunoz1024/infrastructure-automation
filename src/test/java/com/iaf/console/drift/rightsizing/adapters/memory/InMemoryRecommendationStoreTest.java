package com.iaf.console.drift.rightsizing.adapters.memory;

import com.iaf.console.drift.rightsizing.adapters.RecommendationStoreContract;
import com.iaf.console.drift.rightsizing.ports.outbound.RecommendationStorePort;
import com.iaf.console.drift.rightsizing.ports.outbound.UtilisationStorePort;

/** The demo's and the tests' recommendation and utilisation stores keep the MongoDB stores' rules. */
class InMemoryRecommendationStoreTest extends RecommendationStoreContract {

    @Override
    protected RecommendationStorePort freshRecommendations() {
        return new InMemoryRecommendationStore();
    }

    @Override
    protected UtilisationStorePort freshUtilisation() {
        return new InMemoryUtilisationStore();
    }
}
