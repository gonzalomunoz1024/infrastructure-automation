package com.iaf.console.identity.common.auth;

/** No usable credentials: a 401 whose message says why without echoing the token. */
public final class UnauthenticatedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UnauthenticatedException(String message) {
        super(message);
    }
}
