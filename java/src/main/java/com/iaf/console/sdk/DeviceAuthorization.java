package com.iaf.console.sdk;

import java.time.Duration;
import java.time.Instant;

/**
 * What {@code POST /auth/device} answers (RFC 8628 §3.2), with the moment the codes expire.
 *
 * @param deviceCode what the polls send; never shown
 * @param userCode eight letters written {@code XXXX-XXXX}, for the person to type at {@code verificationUri}
 * @param verificationUri the page that approves the sign-in
 * @param verificationUriComplete {@code verificationUri} with the code in it, for a link or a QR
 *     code (null when the BFF sent none)
 * @param expiresIn how long the codes live (10 minutes)
 * @param interval the wait between polls (5 seconds); {@code slow_down} adds 5 seconds
 * @param expiresAt when the codes expire
 */
public record DeviceAuthorization(
        String deviceCode,
        String userCode,
        String verificationUri,
        String verificationUriComplete,
        Duration expiresIn,
        Duration interval,
        Instant expiresAt) {

    /** The device code stays out of logs. */
    @Override
    public String toString() {
        return "DeviceAuthorization[userCode=" + userCode
                + ", verificationUri=" + verificationUri
                + ", expiresAt=" + expiresAt
                + ", interval=" + interval + "]";
    }
}
