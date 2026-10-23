package com.iaf.console.cost.ledger.adapters.web;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.iaf.console.cost.auth.domain.ConsolePrincipal;
import com.iaf.console.cost.ledger.application.CostQueries;
import com.iaf.console.cost.ledger.application.LedgerStatuses;
import com.iaf.console.cost.ledger.application.ResourceCosts;
import com.iaf.console.cost.ledger.application.SyncService;
import com.iaf.console.cost.ledger.domain.CostFilter;
import com.iaf.console.cost.ledger.domain.CostQuery;
import com.iaf.console.cost.ledger.domain.CostReport;
import com.iaf.console.cost.ledger.domain.Granularity;
import com.iaf.console.cost.ledger.domain.LedgerStatus;
import com.iaf.console.cost.ledger.domain.ResourceCost;
import com.iaf.console.cost.ledger.domain.SyncRun;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.wfrn.Wfrn;

import reactor.core.publisher.Mono;

/**
 * The cost surface of the contract: the Cost Explorer's query over one account or many (the
 * BFF's organization view names them), one resource's cost, and the ledger's operator calls for
 * the console's own services only.
 */
@RestController
public final class CostController {

    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final Pattern TAG_PARAM = Pattern.compile("^tag\\.([a-z0-9-]{1,32})$");

    private final CostQueries queries;
    private final ResourceCosts resources;
    private final SyncService syncs;
    private final LedgerStatuses statuses;

    public CostController(CostQueries queries, ResourceCosts resources, SyncService syncs, LedgerStatuses statuses) {
        this.queries = queries;
        this.resources = resources;
        this.syncs = syncs;
        this.statuses = statuses;
    }

    @GetMapping("/v1/cost")
    public Mono<CostReport> cost(ServerWebExchange exchange, @RequestParam(name = "accountId", required = false) List<String> accountIds,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String granularity, @RequestParam(required = false) String groupBy,
            @RequestParam(required = false) Integer top, @RequestParam(required = false) String service,
            @RequestParam(required = false) String type, @RequestParam(required = false) String region,
            @RequestParam(required = false) String wfrn) {
        ConsolePrincipal principal = Principals.of(exchange);
        CostQuery q = query(from, to, granularity, groupBy, top, new CostFilter(service, type, region, tags(exchange.getRequest().getQueryParams())),
                wfrn);
        if (q.wfrn() != null) {
            Wfrn name = parse(q.wfrn());
            return resources.report(principal.scope(accountIds == null || accountIds.isEmpty() ? name.account() : accountIds.get(0), null), q);
        }
        List<AccountScope> scopes = principal.scopes(accountIds, null);
        return queries.report(scopes, q);
    }

    @GetMapping("/v1/wfrn/{wfrn}/cost")
    public Mono<ResourceCost> resourceCost(ServerWebExchange exchange, @PathVariable String wfrn,
            @RequestParam(required = false) String accountId) {
        Wfrn name = parse(wfrn);
        AccountScope scope = Principals.of(exchange).scope(accountId != null ? accountId : name.account(), null);
        if (!scope.accountId().equals(name.account())) {
            return Mono.error(NotFoundException.of("resource", wfrn));
        }
        return resources.cost(scope, wfrn);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SyncRequest(String accountId) {
    }

    @PostMapping("/v1/ledger:sync")
    public Mono<ResponseEntity<SyncRun>> sync(ServerWebExchange exchange, @RequestBody(required = false) SyncRequest body,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String key) {
        Principals.internal(exchange);
        String accountId = body == null || body.accountId() == null || body.accountId().isBlank() ? null : body.accountId().trim();
        if (accountId != null && !AccountScope.isAccountId(accountId)) {
            throw new InvalidInputException("accountId is 12 digits", List.of(Problem.error("pattern", "/accountId", "an account id is 12 digits")));
        }
        if (key != null && key.length() > 255) {
            throw new InvalidInputException("Idempotency-Key is too long", List.of(Problem.error("maxLength", IDEMPOTENCY_KEY, "at most 255 characters")));
        }
        return syncs.start(accountId, key).map(run -> ResponseEntity.status(HttpStatus.ACCEPTED).body(run));
    }

    @GetMapping("/v1/ledger/syncs/{runId}")
    public Mono<SyncRun> syncRun(ServerWebExchange exchange, @PathVariable String runId) {
        Principals.internal(exchange);
        return syncs.find(runId);
    }

    @GetMapping("/v1/ledger/status")
    public Mono<LedgerStatus> status(ServerWebExchange exchange) {
        Principals.internal(exchange);
        return statuses.status();
    }

    static CostQuery query(String from, String to, String granularity, String groupBy, Integer top, CostFilter filter, String wfrn) {
        try {
            return new CostQuery(date("from", from), date("to", to), Granularity.of(granularity), groupBy, top == null ? CostQuery.DEFAULT_TOP : top,
                    filter, wfrn);
        } catch (InvalidInputException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new InvalidInputException(e.getMessage(), List.of(Problem.error("invalid", null, e.getMessage())));
        }
    }

    private static LocalDate date(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidInputException(field + " is required", List.of(Problem.error("required", field, field + " is a date (YYYY-MM-DD)")));
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new InvalidInputException(field + " is not a date", List.of(Problem.error("format", field, field + " is a date (YYYY-MM-DD)")));
        }
    }

    /** The {@code tag.<key>} filters of the query string. */
    static Map<String, String> tags(MultiValueMap<String, String> params) {
        Map<String, String> tags = new LinkedHashMap<>();
        params.forEach((name, values) -> {
            if (name.startsWith("tag.")) {
                var m = TAG_PARAM.matcher(name);
                if (!m.matches()) {
                    throw new InvalidInputException("bad tag filter", List.of(Problem.error("pattern", name, "a tag filter is tag.<key>, key [a-z0-9-]{1,32}")));
                }
                if (!values.isEmpty() && values.get(0) != null) {
                    tags.put(m.group(1), values.get(0));
                }
            }
        });
        return tags;
    }

    private static Wfrn parse(String wfrn) {
        try {
            return Wfrn.parse(wfrn);
        } catch (IllegalArgumentException e) {
            throw new InvalidInputException("not a wfrn", List.of(Problem.error("pattern", "wfrn", e.getMessage())));
        }
    }
}
