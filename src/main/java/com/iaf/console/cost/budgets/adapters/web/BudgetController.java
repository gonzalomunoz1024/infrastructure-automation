package com.iaf.console.cost.budgets.adapters.web;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import com.iaf.console.cost.auth.domain.ConsolePrincipal;
import com.iaf.console.cost.budgets.application.BudgetChecks;
import com.iaf.console.cost.budgets.application.BudgetsService;
import com.iaf.console.cost.budgets.domain.BudgetCheck;
import com.iaf.console.cost.budgets.domain.BudgetInput;
import com.iaf.console.cost.budgets.domain.BudgetStatus;
import com.iaf.console.cost.budgets.domain.BudgetView;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/**
 * The budgets of the contract. A person's token acts in its own account, and changes a budget only
 * with the {@code account-admin} role there (403 otherwise, behind the BFF's own {@code budgets:*}
 * decision); the console services allowed any account name it ({@code accountId} on reads, the
 * scope's on create, the body's on a check). The ETag is the version; a PUT without
 * {@code If-Match} is a 428.
 */
@RestController
public final class BudgetController {

    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final Pattern BUDGET_ID = Pattern.compile("^bud_[0-9a-hjkmnp-tv-z]{26}$");
    private static final Pattern ETAG = Pattern.compile("^(?:W/)?\"?(\\d+)\"?$");

    private final BudgetsService budgets;
    private final BudgetChecks checks;

    public BudgetController(BudgetsService budgets, BudgetChecks checks) {
        this.budgets = budgets;
        this.checks = checks;
    }

    @GetMapping("/v1/budgets")
    public Mono<Page<BudgetView>> list(ServerWebExchange exchange, @RequestParam(required = false) String accountId,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) String after) {
        return budgets.list(Principals.of(exchange).scope(accountId, null), after, limit);
    }

    @PostMapping("/v1/budgets")
    public Mono<ResponseEntity<BudgetView>> create(ServerWebExchange exchange, @RequestBody BudgetInput body,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String key) {
        ConsolePrincipal principal = Principals.of(exchange);
        principal.requireBudgetChanges();
        if (key != null && key.length() > 255) {
            throw new InvalidInputException("Idempotency-Key is too long", List.of(Problem.error("maxLength", IDEMPOTENCY_KEY, "at most 255 characters")));
        }
        String accountId = body == null || body.scope() == null ? null : body.scope().accountId();
        AccountScope scope = principal.scope(accountId, null);
        return budgets.create(scope, body, key, principal.subject())
                .map(b -> ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.ETAG, etag(b)).body(b));
    }

    @GetMapping("/v1/budgets/{budgetId}")
    public Mono<ResponseEntity<BudgetView>> get(ServerWebExchange exchange, @PathVariable String budgetId,
            @RequestParam(required = false) String accountId) {
        return budgets.get(scope(exchange, accountId, budgetId), budgetId).map(b -> ResponseEntity.ok().header(HttpHeaders.ETAG, etag(b)).body(b));
    }

    @PutMapping("/v1/budgets/{budgetId}")
    public Mono<ResponseEntity<BudgetView>> replace(ServerWebExchange exchange, @PathVariable String budgetId,
            @RequestParam(required = false) String accountId, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody BudgetInput body) {
        ConsolePrincipal principal = Principals.of(exchange);
        principal.requireBudgetChanges();
        AccountScope scope = scope(exchange, accountId, budgetId);
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "If-Match is required: the ETag the budget was read with");
        }
        return budgets.replace(scope, budgetId, version(ifMatch), body, principal.subject())
                .map(b -> ResponseEntity.ok().header(HttpHeaders.ETAG, etag(b)).body(b));
    }

    @DeleteMapping("/v1/budgets/{budgetId}")
    public Mono<ResponseEntity<Void>> delete(ServerWebExchange exchange, @PathVariable String budgetId,
            @RequestParam(required = false) String accountId, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        ConsolePrincipal principal = Principals.of(exchange);
        principal.requireBudgetChanges();
        AccountScope scope = scope(exchange, accountId, budgetId);
        Long expected = ifMatch == null || ifMatch.isBlank() || "*".equals(ifMatch.trim()) ? null : version(ifMatch);
        return budgets.delete(scope, budgetId, expected, principal.subject()).thenReturn(ResponseEntity.noContent().<Void>build());
    }

    @GetMapping("/v1/budgets/{budgetId}/periods")
    public Mono<Page<BudgetStatus>> periods(ServerWebExchange exchange, @PathVariable String budgetId,
            @RequestParam(required = false) String accountId, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String after) {
        return budgets.periods(scope(exchange, accountId, budgetId), budgetId, after, limit);
    }

    public record CheckRequest(String accountId, String type, String service, Map<String, String> tags, BigDecimal monthly) {
    }

    @PostMapping("/v1/budgets:check")
    public Mono<BudgetCheck> check(ServerWebExchange exchange, @RequestBody CheckRequest body) {
        if (body == null || body.accountId() == null || !AccountScope.isAccountId(body.accountId())) {
            throw new InvalidInputException("accountId is required", List.of(Problem.error("required", "/accountId", "the account of the create")));
        }
        if (body.monthly() != null && body.monthly().signum() < 0) {
            throw new InvalidInputException("monthly is not negative", List.of(Problem.error("range", "/monthly", "0 or more")));
        }
        AccountScope scope = Principals.of(exchange).scope(body.accountId(), null);
        return checks.check(scope, new BudgetCheck.Request(body.accountId(), body.type(), body.service(), body.tags(), body.monthly()));
    }

    private static AccountScope scope(ServerWebExchange exchange, String accountId, String budgetId) {
        if (!BUDGET_ID.matcher(budgetId).matches()) {
            throw NotFoundException.of("budget", budgetId);
        }
        return Principals.of(exchange).scope(accountId, null);
    }

    private static long version(String ifMatch) {
        Matcher m = ETAG.matcher(ifMatch.trim());
        if (!m.matches()) {
            throw new InvalidInputException("If-Match is not an ETag of this service", List.of(Problem.error("format", "If-Match", "the ETag, e.g. \"3\"")));
        }
        return Long.parseLong(m.group(1));
    }

    private static String etag(BudgetView b) {
        return "\"" + b.version() + "\"";
    }
}
