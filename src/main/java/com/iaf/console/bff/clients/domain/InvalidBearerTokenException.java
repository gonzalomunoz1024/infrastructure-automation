package com.iaf.console.bff.clients.domain;

/**
 * A bearer token the console API does not accept: past its expiry, of a revoked or ended API
 * session, or not one of its access tokens at all (401). The caller learns only that; the reason
 * is for the log, and never holds the token.
 */
public class InvalidBearerTokenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "invalid_token";

    private final String reason;

    public InvalidBearerTokenException(String reason) {
        super("the access token is not valid: refresh it, or run console login again");
        this.reason = reason;
    }

    /** Why, for the log. */
    public String reason() {
        return reason;
    }
}
