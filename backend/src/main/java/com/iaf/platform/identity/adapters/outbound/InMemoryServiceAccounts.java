package com.iaf.platform.identity.adapters.outbound;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.platform.identity.domain.ServiceAccount;
import com.iaf.platform.identity.ports.outbound.ServiceAccountStore;

@Profile("!mongo")
@Component
public class InMemoryServiceAccounts implements ServiceAccountStore {

    private final Map<String, ServiceAccount> byId = new ConcurrentHashMap<>();

    @Override
    public void save(ServiceAccount account) {
        byId.put(account.id(), account);
    }

    @Override
    public Optional<ServiceAccount> byTokenHash(String hash) {
        return byId.values().stream().filter(a -> hash.equals(a.tokenHash())).findFirst();
    }

    @Override
    public List<ServiceAccount> all() {
        return List.copyOf(byId.values());
    }
}
