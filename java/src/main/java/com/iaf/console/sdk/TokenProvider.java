package com.iaf.console.sdk;

import java.util.Objects;

/**
 * Where a client's bearer token comes from. {@link #token()} is asked before every call;
 * {@link #refresh(String)} once when the BFF answers 401 {@code invalid_token} for the token it
 * was sent, and the call is sent again with what it answers.
 */
@FunctionalInterface
public interface TokenProvider {

    /** The access token for the next call; null sends none. */
    String token();

    /**
     * A token to use instead of {@code rejected}, which the BFF no longer takes; null not to retry.
     * The default renews nothing.
     */
    default String refresh(String rejected) {
        return null;
    }

    /** A static access token, never renewed. */
    static TokenProvider of(String accessToken) {
        Objects.requireNonNull(accessToken, "accessToken");
        return () -> accessToken;
    }
}
