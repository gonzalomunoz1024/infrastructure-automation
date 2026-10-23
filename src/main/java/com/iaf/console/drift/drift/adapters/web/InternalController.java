package com.iaf.console.drift.drift.adapters.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.iaf.console.drift.drift.application.InventorySync;
import com.iaf.console.drift.drift.application.RevertFollower;
import com.iaf.console.drift.drift.application.ScanReport;
import com.iaf.console.drift.drift.application.ShardScanner;
import com.iaf.console.drift.drift.domain.Tier;
import com.iaf.console.drift.drift.domain.Watch;
import com.iaf.console.drift.drift.ports.outbound.WatchStorePort;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/**
 * Operations for the console's own services and operators, never a person (a person's call
 * answers 404): the watch list, a read of one resource now, a scan, a sync and a follower pass.
 */
@RestController
public final class InternalController {

    private final WatchStorePort watches;
    private final ShardScanner scanner;
    private final InventorySync sync;
    private final RevertFollower follower;

    public InternalController(WatchStorePort watches, ShardScanner scanner, InventorySync sync, RevertFollower follower) {
        this.watches = watches;
        this.scanner = scanner;
        this.sync = sync;
        this.follower = follower;
    }

    /** The resources under watch in an account, with their tier and next read. */
    @GetMapping("/internal/watches")
    public Mono<Listing<Watch>> watches(ServerWebExchange exchange,
            @RequestParam String accountId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String after) {
        Principals.internal(exchange);
        if (!AccountScope.isAccountId(accountId)) {
            throw new IllegalArgumentException("accountId is 12 digits");
        }
        AccountScope scope = AccountScope.of(accountId, Principals.principal(exchange).region() != null ? Principals.principal(exchange).region() : "global",
                Principals.principal(exchange).subject());
        return watches.list(scope, type, CursorPaging.pageSize(limit), after).map(Listing::of);
    }

    @GetMapping("/internal/watches/{wfrn}")
    public Mono<Watch> watch(ServerWebExchange exchange, @PathVariable String wfrn) {
        Principals.internal(exchange);
        return watches.find(wfrn).switchIfEmpty(Mono.error(() -> NotFoundException.of("watch", wfrn)));
    }

    /** Reads one resource now, whatever its due time. */
    @PostMapping("/internal/watches/{wfrn}:check")
    public Mono<Map<String, Object>> check(ServerWebExchange exchange, @PathVariable String wfrn) {
        Principals.internal(exchange);
        return sync.refresh(wfrn).then(scanner.checkNow(wfrn)).map(outcome -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("wfrn", wfrn);
            out.put("action", outcome.action());
            if (outcome.finding() != null) {
                out.put("findingId", outcome.finding().findingId());
                out.put("status", outcome.finding().status().id());
            }
            return out;
        }).switchIfEmpty(Mono.error(() -> NotFoundException.of("watch", wfrn)));
    }

    /** One pass over every shard, the named tier only when one is given. */
    @PostMapping("/internal/scan")
    public Mono<ScanReport> scan(ServerWebExchange exchange, @RequestParam(required = false) String tier) {
        Principals.internal(exchange);
        Tier only = tier == null || tier.isBlank() ? null : Tier.of(tier.trim());
        if (only == null && tier != null && !tier.isBlank()) {
            throw new IllegalArgumentException("tier is one of: hot, warm, cold");
        }
        return scanner.scanAll(only);
    }

    @PostMapping("/internal/sync")
    public Mono<InventorySync.SyncReport> sync(ServerWebExchange exchange) {
        Principals.internal(exchange);
        return sync.tick();
    }

    @PostMapping("/internal/follow")
    public Mono<Map<String, Integer>> follow(ServerWebExchange exchange) {
        Principals.internal(exchange);
        return follower.tick().map(n -> Map.of("ended", n));
    }
}
