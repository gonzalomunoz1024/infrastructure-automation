package com.iaf.console.bff.jobs.application;

import com.iaf.console.bff.jobs.domain.FailureAdvice;
import com.iaf.console.bff.jobs.domain.FailureFacts;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.platform.model.JobDetail;

import reactor.core.publisher.Mono;

/**
 * The copilot's reading of a failure no rule explains (WP 9.1 behind WP 9.2): the cause and a fix
 * to try for the person in the session; empty when it has none or the copilot is off. It reads
 * and suggests; it never retries anything.
 */
@FunctionalInterface
public interface FailureAdvisor {

    FailureAdvisor NONE = (session, job, facts) -> Mono.empty();

    Mono<FailureAdvice> advise(Session session, JobDetail job, FailureFacts facts);
}
