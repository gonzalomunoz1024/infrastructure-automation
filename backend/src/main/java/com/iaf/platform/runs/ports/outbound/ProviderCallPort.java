package com.iaf.platform.runs.ports.outbound;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.providers.domain.ProviderDefinition.Action;

import reactor.core.publisher.Mono;

/**
 * Calls one provider action ({@code call: provider}). The production adapter speaks HTTP from the
 * provider file (OpenAPI operation or method+path), resolving the credential through
 * {@code CredentialsPort}; the demo adapter answers from each action's recorded {@code sample}.
 * Failures are {@link ProviderCallException}s classified by the provider's {@code errors} table.
 */
public interface ProviderCallPort {

    /**
     * @param jobId      the job making the call (null for a wizard "Try it")
     * @param seq        a per-job counter so recorded samples can mint stable ids ({@code $job.seq})
     * @param environment dev, test or prod: picks the endpoint
     * @param dryRun     true in previews, dry runs and test runs: never touch the real system
     */
    record ProviderCall(String jobId, int seq, ProviderDefinition provider, Action action, String environment, JsonNode input, boolean dryRun) {
    }

    Mono<JsonNode> call(ProviderCall call);

    class ProviderCallException extends RuntimeException {
        private final String errorClass;
        private final boolean retryable;
        private final int status;

        public ProviderCallException(String errorClass, String message, boolean retryable, int status) {
            super(message);
            this.errorClass = errorClass;
            this.retryable = retryable;
            this.status = status;
        }

        public String errorClass() {
            return errorClass;
        }

        public boolean retryable() {
            return retryable;
        }

        public int status() {
            return status;
        }
    }
}
