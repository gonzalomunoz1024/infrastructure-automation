package com.iaf.console.bff.clients.domain;

/**
 * A refusal of the device grant's endpoints, as RFC 6749 §5.2 and RFC 8628 §3.5 name it: the
 * error code, the HTTP status (400, 401 for {@code invalid_client} at the token endpoint, 429 for
 * too many grants from one address) and a sentence. The sentence never repeats a code or a token.
 */
public final class OAuthException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String error;
    private final int status;

    private OAuthException(String error, int status, String message) {
        super(message);
        this.error = error;
        this.status = status;
    }

    public static OAuthException invalidRequest(String message) {
        return new OAuthException("invalid_request", 400, message);
    }

    /** An unknown client at the device authorization endpoint: 400, as RFC 8628 §3.2 answers. */
    public static OAuthException unknownClient() {
        return new OAuthException("invalid_client", 400, "unknown client_id: the console's clients are console-cli and console-sdk");
    }

    /** At the token endpoint: an unknown client, or not the one the grant or the session was started for (401). */
    public static OAuthException invalidClient(String message) {
        return new OAuthException("invalid_client", 401, message);
    }

    public static OAuthException unsupportedGrantType(String message) {
        return new OAuthException("unsupported_grant_type", 400, message);
    }

    public static OAuthException invalidGrant(String message) {
        return new OAuthException("invalid_grant", 400, message);
    }

    public static OAuthException authorizationPending() {
        return new OAuthException("authorization_pending", 400, "the person has not approved the sign-in yet");
    }

    public static OAuthException slowDown(long interval) {
        return new OAuthException("slow_down", 400, "polled too soon; wait " + interval + " seconds between polls");
    }

    /** Too many grants asked for from one address within a minute: 429. */
    public static OAuthException tooManyGrants() {
        return new OAuthException("slow_down", 429, "too many sign-ins started from this address; try again in a minute");
    }

    public static OAuthException accessDenied() {
        return new OAuthException("access_denied", 400, "the person denied the sign-in");
    }

    public static OAuthException expiredToken() {
        return new OAuthException("expired_token", 400, "the device code expired; start again");
    }

    public String error() {
        return error;
    }

    public int status() {
        return status;
    }
}
