package com.iaf.console.bff.trust.application;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The client assertions this replica has accepted, until each would have expired anyway, so one
 * cannot be used twice. Per replica: an assertion replayed to another replica within its minute
 * or so gets the same client the same short token, which is why assertions must be short.
 */
final class AssertionReplays {

    private static final int SWEEP_ABOVE = 1_000;

    private final ConcurrentHashMap<String, Instant> seen = new ConcurrentHashMap<>();

    /** True the first time the client presents the id; false on every later try before it expires. */
    boolean firstUse(String clientId, String jti, Instant forgetAfter, Instant now) {
        if (seen.size() > SWEEP_ABOVE) {
            seen.entrySet().removeIf(e -> e.getValue().isBefore(now));
        }
        return seen.putIfAbsent(clientId + '\u0000' + jti, forgetAfter) == null;
    }
}
