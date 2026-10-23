package com.iaf.console.search.index.adapters.web;

import java.util.List;

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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.search.auth.domain.ConsolePrincipal;
import com.iaf.console.search.boot.SearchProperties;
import com.iaf.console.search.index.application.EventSource;
import com.iaf.console.search.index.application.RebuildService;
import com.iaf.console.search.index.application.SearchQueries;
import com.iaf.console.search.index.domain.IndexStatus;
import com.iaf.console.search.index.domain.RebuildRun;

import reactor.core.publisher.Mono;

/**
 * The index surface of the contract: the status for any caller (a person's own account), the rebuild and its runs for the
 * internal principal only (a user's token finds nothing there), and the sources for operators.
 */
@RestController
public final class IndexController {

    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final SearchQueries queries;
    private final RebuildService rebuilds;
    private final List<EventSource> sources;
    private final String defaultRegion;

    public IndexController(SearchQueries queries, RebuildService rebuilds, List<EventSource> sources, SearchProperties properties) {
        this.queries = queries;
        this.rebuilds = rebuilds;
        this.sources = List.copyOf(sources);
        this.defaultRegion = properties.region();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RebuildRequest(String accountId) {
    }

    /**
     * The status: a person's documents are their account's (another account's name answers 404);
     * the console's own services see the whole index, or the account they name.
     */
    @GetMapping("/v1/index/status")
    public Mono<IndexStatus> status(ServerWebExchange exchange, @RequestParam(required = false) String accountId) {
        ConsolePrincipal principal = Principals.of(exchange);
        boolean wholeIndex = principal.internal() && (accountId == null || accountId.isBlank());
        return queries.status(wholeIndex ? null : principal.scope(accountId, principal.region() != null ? principal.region() : defaultRegion));
    }

    @PostMapping("/v1/index:rebuild")
    public Mono<ResponseEntity<RebuildRun>> rebuild(ServerWebExchange exchange, @RequestBody(required = false) RebuildRequest body,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String key) {
        Principals.internal(exchange);
        String accountId = body == null || body.accountId() == null || body.accountId().isBlank() ? null : body.accountId().trim();
        if (accountId != null && !AccountScope.isAccountId(accountId)) {
            throw new InvalidInputException("accountId is 12 digits", List.of(Problem.error("pattern", "/accountId", "an account id is 12 digits")));
        }
        if (key != null && key.length() > 255) {
            throw new InvalidInputException("Idempotency-Key is too long", List.of(Problem.error("maxLength", "Idempotency-Key", "at most 255 characters")));
        }
        return rebuilds.start(accountId, key).map(run -> ResponseEntity.status(HttpStatus.ACCEPTED).body(run));
    }

    @GetMapping("/v1/index/rebuilds/{runId}")
    public Mono<RebuildRun> run(ServerWebExchange exchange, @PathVariable String runId) {
        Principals.internal(exchange);
        return rebuilds.find(runId).switchIfEmpty(Mono.error(() -> NotFoundException.of("rebuild", runId)));
    }

    @GetMapping("/internal/sources")
    public Mono<List<EventSource.SourceStatus>> sourcesStatus(ServerWebExchange exchange) {
        Principals.internal(exchange);
        return Mono.fromSupplier(() -> sources.stream().map(EventSource::status).toList());
    }
}
