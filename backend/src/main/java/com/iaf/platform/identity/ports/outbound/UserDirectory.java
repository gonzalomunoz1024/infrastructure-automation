package com.iaf.platform.identity.ports.outbound;

import java.util.Optional;

import com.iaf.platform.common.domain.Identity;

/** Looks a person up by id (dev mode and demo data). Production reads the IdP's claims instead. */
public interface UserDirectory {
    Optional<Identity> byId(String id);
}
