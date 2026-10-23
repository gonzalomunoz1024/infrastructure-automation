package com.iaf.console.sdk;

/**
 * An answer of the BFF's OAuth endpoints ({@code /auth/device}, {@code /auth/token},
 * {@code /auth/revoke}): an RFC 6749 §5.2 error such as {@code access_denied},
 * {@code expired_token} or {@code invalid_grant}.
 */
public class OAuthException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int status;
    private final String error;
    private final String description;

    public OAuthException(int status, String error, String description) {
        this(status, error, description, null);
    }

    public OAuthException(int status, String error, String description, Throwable cause) {
        super(description == null ? error : error + ": " + description, cause);
        this.status = status;
        this.error = error;
        this.description = description;
    }

    /** The HTTP status (400 or 401 for an OAuth error). */
    public int status() {
        return status;
    }

    /** {@code authorization_pending}, {@code slow_down}, {@code access_denied}, {@code expired_token}, {@code invalid_grant}, … */
    public String error() {
        return error;
    }

    /** The {@code error_description}, or null. */
    public String description() {
        return description;
    }
}
