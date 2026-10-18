package com.iaf.platform.providers.adapters.outbound;

import com.iaf.platform.providers.ports.outbound.ReviewEvidencePort;

class InMemoryReviewEvidenceContractTest extends ReviewEvidenceContract {

    @Override
    ReviewEvidencePort newStore() {
        return new InMemoryReviewEvidence();
    }

    @Override
    ReviewEvidencePort reopen() {
        return null;
    }
}
