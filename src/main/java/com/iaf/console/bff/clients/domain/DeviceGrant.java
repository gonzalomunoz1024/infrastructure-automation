package com.iaf.console.bff.clients.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.iaf.console.bff.session.domain.AccountMembership;
import com.iaf.console.bff.session.domain.Principal;
import com.iaf.console.bff.session.domain.Session;

/**
 * A device grant (RFC 8628): a CLI or SDK asked to be signed in and polls the token endpoint while
 * the person decides in a signed-in browser. It holds the user code in its canonical form, only the
 * SHA-256 of the device code, the client, where the request came from, the polling interval (which
 * {@code slow_down} lengthens), the decision and, once approved, who approved it in which account
 * and region and when they had signed in, which is what the API session opens with. A device code
 * is redeemed once, and the grant then names the API session it opened. {@code version} grows
 * with every change, so a store replaces a grant only as it was read.
 */
public record DeviceGrant(
        String userCode,
        String deviceCodeHash,
        String clientId,
        Status status,
        Instant requestedAt,
        Instant expiresAt,
        RequestedFrom requestedFrom,
        long interval,
        Instant lastPolledAt,
        Instant decidedAt,
        Approver approver,
        Instant redeemedAt,
        String apiSessionId,
        long version) {

    /** How long an expired grant is still shown, and so kept, before it is a 404. */
    public static final Duration KEPT_AFTER_EXPIRY = Duration.ofMinutes(10);
    /** What each {@code slow_down} adds to the grant's interval, in seconds (RFC 8628 §3.5). */
    public static final long SLOW_DOWN_SECONDS = 5;

    /** The decision. An expired grant is still {@code PENDING}: expiry is read from the time. */
    public enum Status {
        PENDING, APPROVED, DENIED
    }

    /** What a poll of the token endpoint answers (RFC 8628 §3.5), or that the device code is now redeemed. */
    public enum Outcome {
        AUTHORIZATION_PENDING, SLOW_DOWN, ACCESS_DENIED, EXPIRED_TOKEN, ALREADY_REDEEMED, REDEEMED
    }

    /** A poll's outcome and the grant to keep after it; {@code next} is null when the poll changes nothing. */
    public record Polled(Outcome outcome, DeviceGrant next) {
    }

    /**
     * Who approved the grant, as their browser session said at that moment: the person at the
     * identity provider and in the console, their memberships, the account and region the session
     * was in, and when they last signed in. Never the browser session's id, which is its cookie.
     */
    public record Approver(String issuer, String subject, Principal user, List<AccountMembership> accounts, String accountId, String region,
            Instant authTime) {

        public Approver {
            Objects.requireNonNull(user, "user");
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(region, "region");
            Objects.requireNonNull(authTime, "authTime");
            accounts = accounts == null ? List.of() : List.copyOf(accounts);
        }

        public static Approver of(Session session) {
            return new Approver(session.issuer(), session.subject(), session.user(), session.accounts(), session.accountId(), session.region(),
                    session.authTime());
        }
    }

    public DeviceGrant {
        Objects.requireNonNull(userCode, "userCode");
        Objects.requireNonNull(deviceCodeHash, "deviceCodeHash");
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(requestedAt, "requestedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        requestedFrom = requestedFrom == null ? new RequestedFrom(null, null) : requestedFrom;
    }

    /** A new grant, pending until {@code now + ttl}, polled at most every {@code interval}. */
    public static DeviceGrant requested(String userCode, String deviceCodeHash, String clientId, RequestedFrom from, Instant now, Duration ttl,
            Duration interval) {
        return new DeviceGrant(userCode, deviceCodeHash, clientId, Status.PENDING, now, now.plus(ttl), from, interval.toSeconds(), null, null, null,
                null, null, 1);
    }

    public boolean expired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /** Whether the grant is still shown: until ten minutes past its expiry. */
    public boolean kept(Instant now) {
        return now.isBefore(purgeAt());
    }

    /** When a store may forget the grant. */
    public Instant purgeAt() {
        return expiresAt.plus(KEPT_AFTER_EXPIRY);
    }

    /** {@code pending}, {@code approved}, {@code denied} or {@code expired}: an undecided grant past its time is expired. */
    public String statusAt(Instant now) {
        if (status == Status.PENDING && expired(now)) {
            return "expired";
        }
        return status.name().toLowerCase(Locale.ROOT);
    }

    /** The grant approved by a person; refused once decided (409) or after expiry (410). */
    public DeviceGrant approve(Approver by, Instant now) {
        requireUndecided(now);
        return new DeviceGrant(userCode, deviceCodeHash, clientId, Status.APPROVED, requestedAt, expiresAt, requestedFrom, interval, lastPolledAt, now,
                Objects.requireNonNull(by, "by"), redeemedAt, apiSessionId, version + 1);
    }

    /** The grant denied; refused once decided (409) or after expiry (410). */
    public DeviceGrant deny(Instant now) {
        requireUndecided(now);
        return new DeviceGrant(userCode, deviceCodeHash, clientId, Status.DENIED, requestedAt, expiresAt, requestedFrom, interval, lastPolledAt, now,
                null, redeemedAt, apiSessionId, version + 1);
    }

    /** Throws when the grant cannot be decided any more: decided already (409), or past its expiry (410). */
    public void requireUndecided(Instant now) {
        if (status != Status.PENDING) {
            throw new DeviceGrantDecidedException(statusAt(now));
        }
        if (expired(now)) {
            throw new DeviceGrantExpiredException(expiresAt);
        }
    }

    /**
     * The token endpoint polled with this grant's device code, as RFC 8628 §3.5 has it: a device
     * code redeemed before is refused, a denied grant is {@code access_denied}, an expired one
     * {@code expired_token}, an approved one is redeemed now for the API session
     * {@code sessionId} names. A pending grant answers {@code authorization_pending}, or
     * {@code slow_down} when polled sooner than its interval after the poll before, which adds five
     * seconds to the interval for every later poll.
     */
    public Polled poll(Instant now, String sessionId) {
        if (redeemedAt != null) {
            return new Polled(Outcome.ALREADY_REDEEMED, null);
        }
        if (status == Status.DENIED) {
            return new Polled(Outcome.ACCESS_DENIED, null);
        }
        if (expired(now)) {
            return new Polled(Outcome.EXPIRED_TOKEN, null);
        }
        if (status == Status.APPROVED) {
            return new Polled(Outcome.REDEEMED, new DeviceGrant(userCode, deviceCodeHash, clientId, status, requestedAt, expiresAt, requestedFrom,
                    interval, now, decidedAt, approver, now, Objects.requireNonNull(sessionId, "sessionId"), version + 1));
        }
        boolean tooSoon = lastPolledAt != null && now.isBefore(lastPolledAt.plusSeconds(interval));
        long nextInterval = tooSoon ? interval + SLOW_DOWN_SECONDS : interval;
        DeviceGrant next = new DeviceGrant(userCode, deviceCodeHash, clientId, status, requestedAt, expiresAt, requestedFrom, nextInterval, now,
                decidedAt, approver, redeemedAt, apiSessionId, version + 1);
        return new Polled(tooSoon ? Outcome.SLOW_DOWN : Outcome.AUTHORIZATION_PENDING, next);
    }
}
