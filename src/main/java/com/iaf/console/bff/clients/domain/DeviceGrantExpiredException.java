package com.iaf.console.bff.clients.domain;

import java.time.Instant;

/** A device grant decided after it expired: 410 {@code device-grant.expired}, with when it did. */
public class DeviceGrantExpiredException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "device-grant.expired";

    private final Instant expiresAt;

    public DeviceGrantExpiredException(Instant expiresAt) {
        super("the device sign-in expired; start it again with console login");
        this.expiresAt = expiresAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }
}
