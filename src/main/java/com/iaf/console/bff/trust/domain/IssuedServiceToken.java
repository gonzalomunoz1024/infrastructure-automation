package com.iaf.console.bff.trust.domain;

import java.util.List;

/** A service token as issued: the compact JWS, how many seconds it lives, the services it is for, and whom it was issued to. */
public record IssuedServiceToken(String token, long expiresIn, List<String> audience, String clientId) {

    public IssuedServiceToken {
        audience = List.copyOf(audience);
    }
}
