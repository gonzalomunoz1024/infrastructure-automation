package com.iaf.console.drift.drift.adapters.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.drift.drift.application.DiscoveryService;
import com.iaf.console.drift.drift.domain.DiscoveryRun;
import com.iaf.console.drift.drift.domain.UnmanagedObject;
import com.iaf.console.drift.drift.ports.outbound.DiscoveryStorePort;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/** Discovery and the unmanaged objects it finds: start a run, read it, list what is unmanaged, import one, hand one to another team. */
@RestController
public final class DiscoveryController {

    private final DiscoveryService discovery;

    public DiscoveryController(DiscoveryService discovery) {
        this.discovery = discovery;
    }

    @PostMapping("/v1/discover")
    public Mono<ResponseEntity<ObjectNode>> start(ServerWebExchange exchange,
            @RequestParam(required = false) String accountId,
            @RequestHeader(value = FindingController.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody(required = false) Requests.DiscoverRequest body) {
        List<Problem> problems = new ArrayList<>();
        if (body == null || body.type() == null || body.type().isBlank()) {
            problems.add(Problem.error("type.required", "type", "the resource type to discover"));
        }
        if (body == null || body.region() == null || body.region().isBlank()) {
            problems.add(Problem.error("region.required", "region", "the region to discover in"));
        }
        if (!problems.isEmpty()) {
            throw new InvalidInputException("invalid discovery request", problems);
        }
        AccountScope scope = Principals.scope(exchange, accountId, body.region());
        return discovery.start(scope, body.type(), body.region(), idempotencyKey).map(Principals::respond);
    }

    @GetMapping("/v1/discover/{runId}")
    public Mono<DiscoveryRun> get(ServerWebExchange exchange, @PathVariable String runId, @RequestParam(required = false) String accountId) {
        return discovery.get(Principals.scope(exchange, accountId, null), runId);
    }

    /** The owner filters of the list; anything else is a 400, never an empty page. */
    private static final Set<String> OWNERS = Set.of(DiscoveryStorePort.ANY, DiscoveryStorePort.MINE, DiscoveryStorePort.UNKNOWN);
    private static final int NOTE_MAX = 500;

    @GetMapping("/v1/unmanaged")
    public Mono<Listing<UnmanagedObject>> unmanaged(ServerWebExchange exchange,
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String owner,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String after) {
        if (owner != null && !OWNERS.contains(owner)) {
            throw new IllegalArgumentException("owner is one of: any, mine, unknown");
        }
        AccountScope scope = Principals.scope(exchange, accountId, region);
        return discovery.listUnmanaged(scope, type, region, owner, CursorPaging.pageSize(limit), after).map(Listing::of);
    }

    @PostMapping("/v1/unmanaged:import")
    public Mono<ResponseEntity<ObjectNode>> importUnmanaged(ServerWebExchange exchange,
            @RequestParam(required = false) String accountId,
            @RequestHeader(value = FindingController.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestHeader(value = FindingController.REQUEST_ID, required = false) String requestId,
            @RequestBody(required = false) Requests.ImportRequest body) {
        List<Problem> problems = new ArrayList<>();
        if (body == null || body.type() == null || body.type().isBlank()) {
            problems.add(Problem.error("type.required", "type", "the resource type of the object"));
        }
        if (body == null || body.region() == null || body.region().isBlank()) {
            problems.add(Problem.error("region.required", "region", "the region the object is in"));
        }
        if (body == null || body.providerId() == null || body.providerId().isBlank()) {
            problems.add(Problem.error("providerId.required", "providerId", "the provider's id of the object"));
        }
        if (!problems.isEmpty()) {
            throw new InvalidInputException("invalid import request", problems);
        }
        AccountScope scope = Principals.scope(exchange, accountId, body.region());
        return discovery.importUnmanaged(scope, body.type(), body.region(), body.providerId(), body.name(), Principals.principal(exchange).subject(),
                idempotencyKey, requestId).map(Principals::respond);
    }

    @PostMapping("/v1/unmanaged:assign")
    public Mono<ResponseEntity<ObjectNode>> assign(ServerWebExchange exchange,
            @RequestParam(required = false) String accountId,
            @RequestHeader(value = FindingController.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody(required = false) Requests.AssignRequest body) {
        List<Problem> problems = new ArrayList<>();
        if (body == null || body.type() == null || body.type().isBlank()) {
            problems.add(Problem.error("type.required", "type", "the resource type of the object"));
        }
        if (body == null || body.region() == null || body.region().isBlank()) {
            problems.add(Problem.error("region.required", "region", "the region the object is in"));
        }
        if (body == null || body.providerId() == null || body.providerId().isBlank()) {
            problems.add(Problem.error("providerId.required", "providerId", "the provider's id of the object"));
        }
        if (body == null || body.accountId() == null || body.accountId().isBlank()) {
            problems.add(Problem.error("accountId.required", "accountId", "the account to hand the object to"));
        }
        if (body != null && body.note() != null && body.note().length() > NOTE_MAX) {
            problems.add(Problem.error("note.tooLong", "note", "a note is at most " + NOTE_MAX + " characters"));
        }
        if (!problems.isEmpty()) {
            throw new InvalidInputException("invalid assignment", problems);
        }
        AccountScope scope = Principals.scope(exchange, accountId, body.region());
        String actor = body.actor() != null && !body.actor().isBlank() ? body.actor() : Principals.principal(exchange).subject();
        String note = body.note() == null || body.note().isBlank() ? null : body.note().strip();
        return discovery.assign(scope, body.type(), body.region(), body.providerId(), body.accountId().strip(), note, actor, idempotencyKey)
                .map(Principals::respond);
    }
}
