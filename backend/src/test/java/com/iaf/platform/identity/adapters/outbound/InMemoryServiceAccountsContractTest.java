package com.iaf.platform.identity.adapters.outbound;

import com.iaf.platform.identity.ports.outbound.ServiceAccountStore;

class InMemoryServiceAccountsContractTest extends ServiceAccountStoreContract {

    @Override
    ServiceAccountStore newStore() {
        return new InMemoryServiceAccounts();
    }

    @Override
    ServiceAccountStore reopen() {
        return null;
    }
}
