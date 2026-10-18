package com.iaf.platform.releases.ports.inbound;

import reactor.core.publisher.Mono;

/** Read side of releases; the catalog asks it which version a new job runs, the providers slice which version a call uses. */
public interface ReleaseQueries {

    /**
     * The version to run for a new job of a workflow, given its published version: during a canary
     * a share of jobs (the current stage's percentage) runs the candidate, the rest the previous
     * version. Returns the published version when no release is active.
     */
    Mono<String> route(String workflow, String publishedVersion);

    /**
     * The provider version a job's calls use while a provider release is active: the candidate in
     * sandbox use (the {@code dev} environment and dry runs) and for the current stage's share of
     * jobs, the current version for the rest and while the release is paused. A job keeps the
     * version it was routed to for all its calls. Empty when no provider release is active.
     */
    default Mono<String> routeProvider(String provider, String jobId, String environment, boolean dryRun) {
        return Mono.empty();
    }

    /** The candidate version of the provider's active release; empty when none is active. */
    default Mono<String> providerCandidate(String provider) {
        return Mono.empty();
    }
}
