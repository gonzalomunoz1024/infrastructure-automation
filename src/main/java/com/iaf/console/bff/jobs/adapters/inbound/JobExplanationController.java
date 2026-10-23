package com.iaf.console.bff.jobs.adapters.inbound;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.iaf.console.bff.jobs.application.FailureExplainer;
import com.iaf.console.bff.jobs.domain.JobExplanation;
import com.iaf.console.bff.session.application.CurrentSession;

import reactor.core.publisher.Mono;

/** {@code GET /console/v1/jobs/{jobId}/explanation}: why a failed job failed and the fix to try. */
@RestController
public class JobExplanationController {

    private final FailureExplainer explainer;

    public JobExplanationController(FailureExplainer explainer) {
        this.explainer = explainer;
    }

    @GetMapping(path = "/console/v1/jobs/{jobId}/explanation", produces = "application/json")
    public Mono<JobExplanation> explain(@PathVariable("jobId") String jobId, ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> explainer.explain(session, jobId));
    }
}
