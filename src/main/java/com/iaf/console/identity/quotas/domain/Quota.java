package com.iaf.console.identity.quotas.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

/** A quota as the contract serves it: the key, the limit in force, its burst, what is used, where the limit comes from, and an open request. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Quota(String key, int limit, Integer burst, int used, Source source, String pendingRequestId) {

    public enum Source {
        /** The console's default for the key. */
        DEFAULT("default"),
        /** A limit set on this account. */
        OVERRIDE("override"),
        /** The platform's own bucket, read through. */
        PLATFORM("platform");

        private final String json;

        Source(String json) {
            this.json = json;
        }

        @com.fasterxml.jackson.annotation.JsonValue
        public String json() {
            return json;
        }
    }

    public Quota withPendingRequest(String requestId) {
        return new Quota(key, limit, burst, used, source, requestId);
    }
}
