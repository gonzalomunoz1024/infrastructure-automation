package com.iaf.console.drift.drift.ports.outbound;

import java.util.Map;

import com.iaf.console.platform.client.RequestOptions;
import com.iaf.console.platform.model.CatalogEntry;
import com.iaf.console.platform.model.JobAccepted;

import reactor.core.publisher.Mono;

/**
 * Starts a workflow on the platform for an account: with the account's own platform credential
 * (hub decision 18), since the console-internal account may read but never start a job.
 */
public interface WorkflowStarterPort {

    Mono<JobAccepted> start(String accountId, CatalogEntry entry, Map<String, String> pathParams, Map<String, Object> input, RequestOptions options);

    /** No platform credential is at hand for the account: the revert cannot start (a 502 for the caller). */
    final class NoCredential extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public NoCredential(String accountId) {
            super("no platform credential for account " + accountId);
        }
    }
}
