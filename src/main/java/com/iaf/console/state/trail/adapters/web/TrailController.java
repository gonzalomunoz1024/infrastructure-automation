package com.iaf.console.state.trail.adapters.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.kit.errors.ForbiddenException;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.state.auth.domain.ConsolePrincipal;
import com.iaf.console.state.boot.StateProperties;
import com.iaf.console.state.inventory.domain.ActivityQuery;
import com.iaf.console.state.inventory.domain.EventChain;
import com.iaf.console.state.inventory.domain.ResourceEventType;
import com.iaf.console.state.inventory.domain.ResourceIdentity;
import com.iaf.console.state.inventory.domain.ResourceQuery;
import com.iaf.console.state.trail.application.ActivityTrail;
import com.iaf.console.state.trail.domain.Digest;
import com.iaf.console.state.trail.domain.EvidencePack;
import com.iaf.console.wfrn.WfrnPattern;

import reactor.core.publisher.Mono;

/**
 * The trail's HTTP surface: an account's activity, a resource's chain checked, the digests, the
 * keys that signed them, and the evidence pack, which only the account's admins and auditors (and
 * the console's own services) may take.
 */
@RestController
public final class TrailController {

    private final ActivityTrail trail;
    private final String defaultRegion;

    public TrailController(ActivityTrail trail, StateProperties properties) {
        this.trail = trail;
        this.defaultRegion = properties.region();
    }

    /** The contract's page: {@code next} is present on every page, null on the last. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Listing<T>(List<T> items, String next) {
    }

    @GetMapping("/v1/activity")
    public Mono<Listing<ObjectNode>> activity(ServerWebExchange exchange,
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String jobId,
            @RequestParam(required = false) String wfrn,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String after) {
        AccountScope scope = scope(exchange, accountId, region);
        if (type != null && ResourceEventType.of(type) == null) {
            throw new IllegalArgumentException("type is a state.resources event type, such as ResourceRequested or Deleted");
        }
        if (wfrn != null) {
            WfrnPattern.parse(wfrn);
        }
        ActivityQuery query = new ActivityQuery(from, to, type, actor, jobId, wfrn, ResourceQuery.limitOf(limit),
                after == null ? null : CursorPaging.decode(after));
        return trail.activity(scope, query).map(page -> new Listing<>(page.items(), page.next()));
    }

    @GetMapping("/v1/wfrn/{wfrn}/events:verify")
    public Mono<EventChain.Check> verify(ServerWebExchange exchange, @PathVariable String wfrn) {
        ResourceIdentity id = ResourceIdentity.of(wfrn);
        return trail.verify(principal(exchange).scope(id.accountId(), id.region()), wfrn);
    }

    @GetMapping("/v1/activity/digests")
    public Mono<Listing<Digest>> digests(ServerWebExchange exchange,
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        return trail.digests(scope(exchange, accountId, region), from, to).collectList().map(items -> new Listing<>(items, null));
    }

    @GetMapping("/v1/activity/keys")
    public Mono<Map<String, Object>> keys() {
        return trail.keys();
    }

    @GetMapping("/v1/activity/evidence")
    public Mono<EvidencePack> evidence(ServerWebExchange exchange,
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) String region,
            @RequestParam Instant from,
            @RequestParam Instant to) {
        ConsolePrincipal principal = principal(exchange);
        if (!principal.mayAudit()) {
            throw new ForbiddenException("console IAM: an account's evidence pack is for its admins and auditors");
        }
        return trail.evidence(scope(exchange, accountId, region), from, to);
    }

    private AccountScope scope(ServerWebExchange exchange, String accountId, String region) {
        ConsolePrincipal principal = principal(exchange);
        return principal.scope(accountId, region != null ? region : principal.region() != null ? principal.region() : defaultRegion);
    }

    private static ConsolePrincipal principal(ServerWebExchange exchange) {
        ConsolePrincipal principal = exchange.getAttribute(ConsolePrincipal.ATTRIBUTE);
        if (principal == null) {
            throw new IllegalStateException("no principal on the exchange: the authentication filter did not run");
        }
        return principal;
    }
}
