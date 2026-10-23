package com.iaf.console.bff.drift.adapters.inbound;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.iaf.console.bff.drift.application.Unmanaged;
import com.iaf.console.bff.drift.domain.DiscoveryRun;
import com.iaf.console.bff.drift.domain.DriftResults;
import com.iaf.console.bff.drift.domain.UnmanagedObject;
import com.iaf.console.bff.jobs.application.OnBehalfOf;
import com.iaf.console.bff.session.application.CurrentSession;
import com.iaf.console.kit.correlation.Correlation;
import com.iaf.console.kit.paging.Page;

import reactor.core.publisher.Mono;

/**
 * The shadow-infrastructure finder (WP 5.3): {@code /console/v1/discover} (start a scan of a
 * type's provider, follow it) and {@code /console/v1/unmanaged} (what no account manages, and
 * importing or handing over one object), all through the drift service.
 */
@RestController
public class UnmanagedController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final Unmanaged unmanaged;

    public UnmanagedController(Unmanaged unmanaged) {
        this.unmanaged = unmanaged;
    }

    /** The type to scan. */
    public record DiscoverBody(String type) {
    }

    /** The object to take under management, and the name to give it. */
    public record ImportBody(String type, String providerId, String name) {
    }

    /** The object to hand over, the account to hand it to, and a note for that team. */
    public record AssignBody(String type, String providerId, String accountId, String note) {
    }

    @PostMapping(path = "/console/v1/discover", produces = "application/json")
    public Mono<ResponseEntity<DiscoveryRun>> discover(@RequestBody(required = false) DiscoverBody body,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey, ServerWebExchange exchange) {
        return CurrentSession.required(exchange)
                .flatMap(session -> unmanaged.discover(session, body == null ? null : body.type(), requestId(exchange), idempotencyKey))
                .map(run -> ResponseEntity.status(HttpStatus.ACCEPTED).body(run));
    }

    @GetMapping(path = "/console/v1/discover/{runId}", produces = "application/json")
    public Mono<DiscoveryRun> run(@PathVariable("runId") String runId, ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> unmanaged.run(session, runId));
    }

    @GetMapping(path = "/console/v1/unmanaged", produces = "application/json")
    public Mono<Page<UnmanagedObject>> list(@RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "owner", required = false) String owner,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "after", required = false) String after,
            ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> unmanaged.list(session, type, owner, limit, after));
    }

    @PostMapping(path = "/console/v1/unmanaged:import", produces = "application/json")
    public Mono<ResponseEntity<DriftResults.Imported>> importObject(@RequestBody(required = false) ImportBody body,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey, ServerWebExchange exchange) {
        ImportBody b = body == null ? new ImportBody(null, null, null) : body;
        return CurrentSession.required(exchange)
                .flatMap(session -> unmanaged.importObject(session, b.type(), b.providerId(), b.name(), requestId(exchange), idempotencyKey))
                .map(imported -> ResponseEntity.status(HttpStatus.CREATED).body(imported));
    }

    @PostMapping(path = "/console/v1/unmanaged:assign", produces = "application/json")
    public Mono<UnmanagedObject> assign(@RequestBody(required = false) AssignBody body,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey, ServerWebExchange exchange) {
        AssignBody b = body == null ? new AssignBody(null, null, null, null) : body;
        return CurrentSession.required(exchange).flatMap(session -> unmanaged.assign(session, b.type(), b.providerId(), b.accountId(), b.note(),
                requestId(exchange), idempotencyKey));
    }

    private static String requestId(ServerWebExchange exchange) {
        return OnBehalfOf.requestId(Correlation.of(exchange));
    }
}
