package com.iaf.console.bff.jobs.adapters.inbound;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.iaf.console.bff.jobs.application.Jobs;
import com.iaf.console.bff.jobs.application.OnBehalfOf;
import com.iaf.console.bff.jobs.domain.BulkDecision;
import com.iaf.console.bff.jobs.domain.JobView;
import com.iaf.console.bff.resources.application.Resources;
import com.iaf.console.bff.resources.domain.CommandAccepted;
import com.iaf.console.bff.session.application.CurrentSession;
import com.iaf.console.kit.correlation.Correlation;
import com.iaf.console.kit.paging.Page;

import reactor.core.publisher.Mono;

/** {@code /console/v1/jobs}: the account's jobs, one job, cancel, retry, and skip or abort of a paused job. */
@RestController
public class JobsController {

    private final Jobs jobs;
    private final Resources resources;

    public JobsController(Jobs jobs, Resources resources) {
        this.jobs = jobs;
        this.resources = resources;
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

    /** Skips the step a paused job waits on (WP 4.2): 202 with the job, 409 {@code job.not-paused} for a job that is not paused. */
    @PostMapping(path = "/console/v1/jobs/{jobId}:skip", produces = "application/json")
    public Mono<ResponseEntity<CommandAccepted>> skip(@PathVariable("jobId") String jobId, @RequestBody(required = false) SkipBody body,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey, ServerWebExchange exchange) {
        return CurrentSession.required(exchange)
                .flatMap(session -> jobs.skip(session, jobId, body == null ? null : body.step(), OnBehalfOf.requestId(Correlation.of(exchange)), idempotencyKey))
                .map(accepted -> ResponseEntity.accepted().body(accepted));
    }

    /** Aborts a paused job (WP 4.2): its compensations run and it ends failed or rolled back; 409 {@code job.not-paused} otherwise. */
    @PostMapping(path = "/console/v1/jobs/{jobId}:abort", produces = "application/json")
    public Mono<ResponseEntity<CommandAccepted>> abort(@PathVariable("jobId") String jobId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey, ServerWebExchange exchange) {
        return CurrentSession.required(exchange)
                .flatMap(session -> jobs.abort(session, jobId, OnBehalfOf.requestId(Correlation.of(exchange)), idempotencyKey))
                .map(accepted -> ResponseEntity.accepted().body(accepted));
    }

    /**
     * Decides every pending approval of the request and its rows that the person may decide: a
     * bulk request's wave in one go (WP 4.2). 202 with what was decided and what was left.
     */
    @PostMapping(path = "/console/v1/jobs/{jobId}/approvals:decide", consumes = "application/json", produces = "application/json")
    public Mono<ResponseEntity<BulkDecision>> decideApprovals(@PathVariable("jobId") String jobId, @RequestBody ApprovalsController.Decide body,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey, ServerWebExchange exchange) {
        return CurrentSession.required(exchange)
                .flatMap(session -> jobs.decideApprovals(session, jobId, body.decision(), body.comment(), OnBehalfOf.requestId(Correlation.of(exchange)),
                        idempotencyKey))
                .map(decided -> ResponseEntity.accepted().body(decided));
    }

    /** The step to skip; the one the job waits on when left out. */
    public record SkipBody(String step) {
    }

    /** Runs a failed request again (WP 4.2): 202 with the job that now runs it, 409 {@code job.not-failed} for a job that did not fail. */
    @PostMapping(path = "/console/v1/jobs/{jobId}:retry", produces = "application/json")
    public Mono<ResponseEntity<CommandAccepted>> retry(@PathVariable("jobId") String jobId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey, ServerWebExchange exchange) {
        return CurrentSession.required(exchange)
                .flatMap(session -> resources.retry(session, jobId, OnBehalfOf.requestId(Correlation.of(exchange)), idempotencyKey))
                .map(accepted -> ResponseEntity.accepted().body(accepted));
    }
}
