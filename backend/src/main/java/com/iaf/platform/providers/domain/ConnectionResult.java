package com.iaf.platform.providers.domain;

import java.util.List;

/** One environment of "Test connection": reachable or not, how long it took, and why. */
public record ConnectionResult(String environment, boolean ok, long durationMs, String message) {

    /** The body of {@code POST /providers:test-connection}. */
    public record Results(List<ConnectionResult> results) {
        public boolean allOk() {
            return results.stream().allMatch(ConnectionResult::ok);
        }
    }
}
