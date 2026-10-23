package com.iaf.console.state.inventory.adapters.web;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.state.auth.domain.ConsolePrincipal;
import com.iaf.console.state.boot.StateProperties;
import com.iaf.console.state.inventory.application.FeedSource;
import com.iaf.console.state.inventory.application.PendingReaper;
import com.iaf.console.state.inventory.application.RegistryImporter;
import com.iaf.console.state.inventory.application.SensitiveData;

import reactor.core.publisher.Mono;

/**
 * Operations for the console's own services and operators, never a user (a user's JWT answers
 * 404 here, as the identity service does): the account import, the snapshot rebuild, the reaper
 * on demand, the feed status and the crypto-shred of a closed account.
 */
@RestController
public final class InternalController {

    private final RegistryImporter importer;
    private final PendingReaper reaper;
    private final SensitiveData sensitive;
    private final List<FeedSource> feeds;
    private final String defaultRegion;

    public InternalController(RegistryImporter importer, PendingReaper reaper, SensitiveData sensitive, List<FeedSource> feeds,
            StateProperties properties) {
        this.importer = importer;
        this.reaper = reaper;
        this.sensitive = sensitive;
        this.feeds = List.copyOf(feeds);
        this.defaultRegion = properties.region();
    }

    /** Backfills an account's wfrns on the platform and takes its resources under management. */
    @PostMapping("/internal/import")
    public Mono<RegistryImporter.Report> importAccount(ServerWebExchange exchange, @RequestBody Requests.ImportAccountRequest body) {
        internal(exchange);
        if (body.accountId() == null || !AccountScope.isAccountId(body.accountId())) {
            throw new IllegalArgumentException("accountId is 12 digits");
        }
        return importer.importAccount(body.accountId(), body.region() != null ? body.region() : defaultRegion, body.owner());
    }

    /** Walks the platform's whole resource snapshot again. */
    @PostMapping("/internal/rebuild")
    public Mono<RegistryImporter.Report> rebuild(ServerWebExchange exchange) {
        internal(exchange);
        return importer.rebuild();
    }

    /** One pass of the reaper, now. */
    @PostMapping("/internal/reap")
    public Mono<PendingReaper.Report> reap(ServerWebExchange exchange) {
        internal(exchange);
        return reaper.tick();
    }

    /** Destroys a closed account's data key: its sealed values are unreadable from then on. */
    @PostMapping("/internal/accounts/{accountId}:shred")
    public Mono<Map<String, Object>> shred(ServerWebExchange exchange, @PathVariable String accountId) {
        internal(exchange);
        if (!AccountScope.isAccountId(accountId)) {
            throw new IllegalArgumentException("accountId is 12 digits");
        }
        return sensitive.shred(accountId).map(gone -> Map.of("accountId", accountId, "destroyed", gone));
    }

    @GetMapping("/internal/feed")
    public Mono<List<FeedSource.FeedStatus>> feed(ServerWebExchange exchange) {
        internal(exchange);
        return Mono.fromSupplier(() -> feeds.stream().map(FeedSource::status).toList());
    }

    private static void internal(ServerWebExchange exchange) {
        ConsolePrincipal principal = ResourceController.principal(exchange);
        if (!principal.internal()) {
            throw new NotFoundException("not found");
        }
    }
}
