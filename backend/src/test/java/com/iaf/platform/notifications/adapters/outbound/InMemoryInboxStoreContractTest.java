package com.iaf.platform.notifications.adapters.outbound;

import com.iaf.platform.notifications.ports.outbound.InboxStore;

class InMemoryInboxStoreContractTest extends InboxStoreContract {

    @Override
    InboxStore newStore(int keep) {
        return new InMemoryInboxStore(keep);
    }
}
