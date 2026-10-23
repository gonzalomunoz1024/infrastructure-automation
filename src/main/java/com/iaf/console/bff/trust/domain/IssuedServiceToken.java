package com.iaf.console.bff.trust.domain;

/** A service token as issued: the compact JWS, how many seconds it lives, the one service it is for, and whom it was issued to. */
public record IssuedServiceToken(String token, long expiresIn, String audience, String clientId) {
}
