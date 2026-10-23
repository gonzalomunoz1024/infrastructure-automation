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

import com.iaf.console.bff.jobs.application.Approvals;
import com.iaf.console.bff.jobs.application.OnBehalfOf;
import com.iaf.console.bff.jobs.domain.ApprovalView;
import com.iaf.console.bff.session.application.CurrentSession;
import com.iaf.console.kit.correlation.Correlation;
import com.iaf.console.kit.paging.Page;

import reactor.core.publisher.Mono;

/** {@code /console/v1/approvals}: the approvals and the decision on one. */
@RestController
public class ApprovalsController {

    private final Approvals approvals;

    public ApprovalsController(Approvals approvals) {
        this.approvals = approvals;
    }

    /** The body of a decision. */
    public record Decide(String decision, String comment) {
    }

    @GetMapping(path = "/console/v1/approvals", produces = "application/json")
    public Mono<Page<ApprovalView>> list(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "after", required = false) String after,
            ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> approvals.list(session, status, limit, after));
    }

    @PostMapping(path = "/console/v1/approvals/{approvalId}:decide", consumes = "application/json")
    public Mono<ResponseEntity<Void>> decide(@PathVariable("approvalId") String approvalId, @RequestBody Decide body,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey, ServerWebExchange exchange) {
        return CurrentSession.required(exchange)
                .flatMap(session -> approvals.decide(session, approvalId, body.decision(), body.comment(), OnBehalfOf.requestId(Correlation.of(exchange)),
                        idempotencyKey))
                .thenReturn(ResponseEntity.accepted().<Void>build());
    }
}
