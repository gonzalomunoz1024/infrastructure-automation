package com.iaf.platform.identity.ports.outbound;

import java.util.List;
import java.util.Optional;

import com.iaf.platform.identity.domain.ServiceAccount;

public interface ServiceAccountStore {
    void save(ServiceAccount account);

    Optional<ServiceAccount> byTokenHash(String hash);

    List<ServiceAccount> all();
}
