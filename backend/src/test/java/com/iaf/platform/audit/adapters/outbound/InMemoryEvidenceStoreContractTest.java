package com.iaf.platform.audit.adapters.outbound;

import com.iaf.platform.audit.ports.outbound.EvidenceStore;

class InMemoryEvidenceStoreContractTest extends EvidenceStoreContract {

    @Override
    EvidenceStore newStore() {
        return new InMemoryEvidenceStore();
    }

    @Override
    EvidenceStore reopen() {
        return null;
    }
}
