package com.iaf.platform.gateway.application;

import com.iaf.platform.gateway.adapters.outbound.InMemoryIdempotencyStore;
import com.iaf.platform.gateway.ports.outbound.IdempotencyStore;

class IdempotencyHandlerTest extends IdempotencyHandlerContract {

    @Override
    IdempotencyStore newStore() {
        return new InMemoryIdempotencyStore(100);
    }
}
