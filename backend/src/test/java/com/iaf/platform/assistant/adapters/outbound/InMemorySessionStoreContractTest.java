package com.iaf.platform.assistant.adapters.outbound;

import com.iaf.platform.assistant.ports.outbound.SessionStore;

class InMemorySessionStoreContractTest extends SessionStoreContract {

    @Override
    SessionStore newStore() {
        return new InMemorySessionStore();
    }

    @Override
    SessionStore reopen() {
        return null;
    }
}
