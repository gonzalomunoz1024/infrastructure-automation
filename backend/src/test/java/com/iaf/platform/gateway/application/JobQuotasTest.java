package com.iaf.platform.gateway.application;

import com.iaf.platform.gateway.adapters.outbound.InMemoryQuotaStore;
import com.iaf.platform.gateway.ports.outbound.QuotaStore;

class JobQuotasTest extends JobQuotasContract {

    @Override
    QuotaStore newStore() {
        return new InMemoryQuotaStore();
    }
}
