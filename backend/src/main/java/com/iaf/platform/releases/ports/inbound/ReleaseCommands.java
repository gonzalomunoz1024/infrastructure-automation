package com.iaf.platform.releases.ports.inbound;

import com.iaf.platform.common.domain.CommandAccepted;

import reactor.core.publisher.Mono;

/**
 * Write side of releases. Releases start by themselves when a workflow version is published;
 * these commands steer one that is running. Every command needs edit rights on the workflow
 * (its owner team, or admin) and answers 404 for an unknown release and 409 when the release is
 * already finished.
 */
public interface ReleaseCommands {

    /** Judge the gates now: advance, promote, roll back or keep waiting. */
    Mono<CommandAccepted> evaluate(String releaseId);

    /** The candidate takes all traffic now, gates or not. */
    Mono<CommandAccepted> promote(String releaseId);

    /** New jobs go to the current version until {@link #resume}. */
    Mono<CommandAccepted> pause(String releaseId);

    Mono<CommandAccepted> resume(String releaseId);

    /** The candidate gets no more jobs; the current version stays. */
    Mono<CommandAccepted> rollback(String releaseId);
}
