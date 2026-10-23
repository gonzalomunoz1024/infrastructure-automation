package com.iaf.console.bff.jobs.adapters.inbound;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.iaf.console.bff.jobs.application.Jobs;
import com.iaf.console.bff.jobs.application.OnBehalfOf;
import com.iaf.console.bff.jobs.domain.JobView;
import com.iaf.console.bff.session.application.CurrentSession;
import com.iaf.console.kit.correlation.Correlation;
import com.iaf.console.kit.paging.Page;

import reactor.core.publisher.Mono;

/** {@code /console/v1/jobs}: the account's jobs, one job, cancel. */
@RestController
public class JobsController {

    private final Jobs jobs;

    public JobsController(Jobs jobs) {
        this.jobs = jobs;
    }

    @GetMapping(path = "/console/v1/jobs", produces = "application/json")
    public Mono<Page<JobView>> list(
            @RequestParam(name = "wfrn", required = false) String wfrn,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "workflow", required = false) String workflow,
            @RequestParam(name = "requester", required = false) String requester,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "after", required = false) String after,
            ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> jobs.list(session, new Jobs.Filters(wfrn, status, workflow, requester, limit, after)));
    }

    @GetMapping(path = "/console/v1/jobs/{jobId}", produces = "application/json")
    public Mono<JobView> get(@PathVariable("jobId") String jobId, ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> jobs.get(session, jobId));
    }

    @PostMapping(path = "/console/v1/jobs/{jobId}:cancel")
    public Mono<ResponseEntity<Void>> cancel(@PathVariable("jobId") String jobId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey, ServerWebExchange exchange) {
        return CurrentSession.required(exchange)
                .flatMap(session -> jobs.cancel(session, jobId, OnBehalfOf.requestId(Correlation.of(exchange)), idempotencyKey))
                .thenReturn(ResponseEntity.accepted().<Void>build());
    }
}
