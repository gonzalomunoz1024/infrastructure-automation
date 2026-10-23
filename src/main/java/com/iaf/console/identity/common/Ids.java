package com.iaf.console.identity.common;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.random.RandomGenerator;

import com.iaf.console.wfrn.Ulid;

/**
 * The ids this service mints: a prefix and a ULID (time ordered, so a collection's natural order
 * is creation order), and twelve-digit account ids. Built from a clock and a random source so a
 * demo seed can be deterministic.
 */
public final class Ids {

    private final Ulid ulids;
    private final RandomGenerator random;

    public Ids(Clock clock, RandomGenerator random) {
        this.ulids = new Ulid(clock, random);
        this.random = random;
    }

    public static Ids secure(Clock clock) {
        return new Ids(clock, new SecureRandom());
    }

    public String ulid() {
        return ulids.generate();
    }

    public String user() {
        return "user_" + ulid();
    }

    public String group() {
        return "grp_" + ulid();
    }

    public String role() {
        return "role_" + ulid();
    }

    public String policy() {
        return "pol_" + ulid();
    }

    /** The public part of an access key, as the contract shows it. */
    public String accessKey() {
        return "ck_" + ulid();
    }

    public String quotaRequest() {
        return "qreq_" + ulid();
    }

    public String accessRequest() {
        return "areq_" + ulid();
    }

    public String event() {
        return "evt_" + ulid();
    }

    /** A twelve-digit account id that never starts with zero, so it reads like AWS's and survives a number column. */
    public String accountId() {
        StringBuilder sb = new StringBuilder(12);
        sb.append((char) ('1' + random.nextInt(9)));
        for (int i = 1; i < 12; i++) {
            sb.append((char) ('0' + random.nextInt(10)));
        }
        return sb.toString();
    }

    /** The secret part of an access key: 32 random bytes, base64url without padding. */
    public String secret() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
