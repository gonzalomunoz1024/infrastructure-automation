package com.iaf.platform.identity.domain;

import java.time.Instant;
import java.util.List;

import com.iaf.platform.common.domain.Identity;

/** A non-human caller (automation, an agent). The token hash is stored, never the token. */
public record ServiceAccount(String id, String name, List<String> roles, List<String> teams, String tokenHash, Instant createdAt) {

    public Identity identity() {
        return new Identity(id, name, null, teams, roles, false, Identity.Kind.SERVICE);
    }
}
