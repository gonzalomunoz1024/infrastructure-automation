package com.iaf.platform.providers.domain;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * How a provider is doing over the last 24 hours, judged by {@link ProviderHealthRule} against the
 * provider's SLO. {@code errorTrend} is failed calls per hour, oldest hour first; {@code reasons}
 * are sentences the admin view shows next to the band.
 */
public record Health(
        Status status,
        double successRate,
        long p50Ms,
        long p95Ms,
        int calls,
        List<Integer> errorTrend,
        List<String> reasons) {

    public enum Status {
        HEALTHY(0), DEGRADED(1), FAILING(2), UNKNOWN(0);

        private final int rank;

        Status(int rank) {
            this.rank = rank;
        }

        /** How bad the band is: an alert fires when the rank goes up. Unknown counts as healthy. */
        public int rank() {
            return rank;
        }

        @JsonValue
        public String json() {
            return name().toLowerCase();
        }
    }

    public boolean known() {
        return status != Status.UNKNOWN;
    }
}
