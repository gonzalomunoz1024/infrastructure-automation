package com.iaf.platform.releases.ports.inbound;

import reactor.core.publisher.Mono;

/** Read side of releases; the catalog asks it which version a new job runs. */
public interface ReleaseQueries {

    /**
     * The version to run for a new job of a workflow, given its published version: during a canary
     * a share of jobs (the current stage's percentage) runs the candidate, the rest the previous
     * version. Returns the published version when no release is active.
     */
    Mono<String> route(String workflow, String publishedVersion);
}
