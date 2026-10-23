package com.iaf.console.cost.budgets.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import com.iaf.console.cost.budgets.domain.Budget;
import com.iaf.console.cost.budgets.domain.BudgetInput;
import com.iaf.console.cost.budgets.domain.BudgetPeriod;
import com.iaf.console.cost.budgets.domain.BudgetRules;
import com.iaf.console.cost.budgets.domain.BudgetScope;
import com.iaf.console.cost.budgets.domain.BudgetStatus;
import com.iaf.console.cost.budgets.domain.BudgetView;
import com.iaf.console.cost.budgets.ports.outbound.BudgetPeriodStorePort;
import com.iaf.console.cost.budgets.ports.outbound.BudgetStorePort;
import com.iaf.console.cost.ledger.application.CostQueries;
import com.iaf.console.cost.pricing.application.Pricing;
import com.iaf.console.cost.pricing.domain.PricingCatalog;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.kit.paging.Cursor;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.wfrn.Ulid;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The budgets: create (idempotent on its key), read with this month's status, replace and delete
 * by version, and the months they had. The status is computed from the ledger when asked, so it
 * is as fresh as the ledger; the thresholds alerted come from the evaluator's record of the month.
 */
public final class BudgetsService {

    /** The principal the evaluator acts as. */
    public static final String SELF = "console-cost-service";
    /** The most budgets one account may hold. */
    public static final int MAX_PER_ACCOUNT = 100;

    private final BudgetStorePort budgets;
    private final BudgetPeriodStorePort periods;
    private final CostQueries costs;
    private final Pricing pricing;
    private final BudgetEvents events;
    private final Clock clock;

    public BudgetsService(BudgetStorePort budgets, BudgetPeriodStorePort periods, CostQueries costs, Pricing pricing, BudgetEvents events,
            Clock clock) {
        this.budgets = budgets;
        this.periods = periods;
        this.costs = costs;
        this.pricing = pricing;
        this.events = events;
        this.clock = clock;
    }

    public Mono<BudgetView> create(AccountScope scope, BudgetInput input, String idempotencyKey, String actor) {
        String hash = hash(input);
        Mono<BudgetView> fresh = Mono.defer(() -> currency().flatMap(currency -> {
            BudgetInput.Valid valid = input.validate(currency);
            return budgets.count(scope).flatMap(n -> {
                if (n >= MAX_PER_ACCOUNT) {
                    return Mono.error(new InvalidInputException("too many budgets",
                            List.of(Problem.error("quota", null, "an account holds at most " + MAX_PER_ACCOUNT + " budgets"))));
                }
                Instant now = clock.instant();
                Budget b = new Budget(Budget.ID_PREFIX + Ulid.next().toLowerCase(Locale.ROOT), valid.name(), new BudgetScope(scope.accountId(), valid.tag()),
                        valid.amountMicros(), valid.currency(), valid.thresholds(), valid.blockCreates(), 1, actor, now, now, idempotencyKey, hash);
                return budgets.insert(b).flatMap(saved -> events.changed(saved, "created", actor, now).then(view(scope, saved)));
            });
        }));
        if (idempotencyKey == null) {
            return fresh;
        }
        return budgets.findByIdempotencyKey(scope, idempotencyKey).flatMap(existing -> hash.equals(existing.requestHash()) ? view(scope, existing)
                : Mono.<BudgetView>error(new ConflictException("the Idempotency-Key was used with another body"))).switchIfEmpty(fresh)
                .onErrorResume(ConflictException.class, e -> idempotencyKey == null ? Mono.error(e)
                        : budgets.findByIdempotencyKey(scope, idempotencyKey).filter(b -> hash.equals(b.requestHash())).flatMap(b -> view(scope, b))
                                .switchIfEmpty(Mono.error(e)));
    }

    public Mono<BudgetView> get(AccountScope scope, String budgetId) {
        return find(scope, budgetId).flatMap(b -> view(scope, b));
    }

    public Mono<Budget> find(AccountScope scope, String budgetId) {
        return budgets.find(scope, budgetId).switchIfEmpty(Mono.error(() -> NotFoundException.of("budget", budgetId)));
    }

    public Mono<Page<BudgetView>> list(AccountScope scope, String after, Integer limit) {
        int size = CursorPaging.pageSize(limit);
        String afterName = null;
        String afterId = null;
        if (after != null && !after.isBlank()) {
            Cursor c = CursorPaging.decode(after);
            afterName = c.sortText();
            afterId = c.id();
        }
        return budgets.list(scope, afterName, afterId, size).collectList()
                .map(fetched -> Page.from(fetched, size, b -> new Cursor(b.name().toLowerCase(Locale.ROOT), b.budgetId())))
                .flatMap(page -> Flux.fromIterable(page.items()).concatMap(b -> view(scope, b)).collectList().map(items -> new Page<>(items, page.next())));
    }

    /** Replaces a budget at the version {@code If-Match} named; 409 when it moved. */
    public Mono<BudgetView> replace(AccountScope scope, String budgetId, long expectedVersion, BudgetInput input, String actor) {
        return find(scope, budgetId).flatMap(current -> currency().flatMap(currency -> {
            if (current.version() != expectedVersion) {
                return Mono.error(new ConflictException("the budget is at version " + current.version()));
            }
            if (input.scope() != null && !sameScope(current.scope(), input.scope(), scope.accountId())) {
                return Mono.error(new InvalidInputException("the scope never changes",
                        List.of(Problem.error("immutable", "/scope", "a budget for another scope is a new budget"))));
            }
            Instant now = clock.instant();
            Budget next = current.replaced(input.validate(currency), now);
            return budgets.replace(next, expectedVersion).flatMap(ok -> ok
                    ? events.changed(next, "updated", actor, now).then(view(scope, next))
                    : Mono.error(new ConflictException("the budget moved")));
        }));
    }

    public Mono<Void> delete(AccountScope scope, String budgetId, Long expectedVersion, String actor) {
        return find(scope, budgetId).flatMap(current -> {
            if (expectedVersion != null && current.version() != expectedVersion) {
                return Mono.error(new ConflictException("the budget is at version " + current.version()));
            }
            return budgets.delete(scope.accountId(), budgetId, current.version()).flatMap(ok -> ok
                    ? events.changed(current, "deleted", actor, clock.instant()).then(periods.deleteAll(scope.accountId(), budgetId))
                    : Mono.error(new ConflictException("the budget moved")));
        });
    }

    /** The budget's months newest first; the cursor is the last period shown. */
    public Mono<Page<BudgetStatus>> periods(AccountScope scope, String budgetId, String after, Integer limit) {
        int size = CursorPaging.pageSize(limit);
        String before = after == null || after.isBlank() ? null : CursorPaging.decode(after).sortText();
        return find(scope, budgetId).flatMap(b -> periods.list(scope, budgetId, before, size).collectList()
                .map(fetched -> Page.from(fetched, size, p -> new Cursor(p.period(), p.budgetId())).map(BudgetRules::status)));
    }

    /** The budget with this month's status, from the ledger now and the alerts the evaluator sent. */
    public Mono<BudgetView> view(AccountScope scope, Budget b) {
        return status(scope, b).map(s -> BudgetView.of(b, s));
    }

    public Mono<BudgetStatus> status(AccountScope scope, Budget b) {
        return current(scope, b).map(BudgetRules::status);
    }

    /** This month's period of a budget: the spend now, with the alerts already recorded. */
    public Mono<BudgetPeriod> current(AccountScope scope, Budget b) {
        AccountScope own = AccountScope.of(b.accountId(), scope.region() != null ? scope.region() : BudgetEvents.REGION, scope.principal());
        return costs.spend(List.of(own), b.scope().filter()).flatMap(spend -> periods.find(own, b.budgetId(), spend.period())
                .map(BudgetPeriod::alertsSent).defaultIfEmpty(List.of())
                .map(sent -> new BudgetPeriod(b.budgetId(), b.accountId(), spend.period(), b.amountMicros(), spend.monthToDate(),
                        Math.max(spend.band().monthEnd(), spend.monthToDate()), sent, spend.asOf(), false)));
    }

    /** The current period's name, for a caller that has no spend at hand. */
    public String period() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC)).toString().substring(0, 7);
    }

    private Mono<String> currency() {
        return pricing.catalog().map(c -> c.lists().stream().map(l -> l.currency()).findFirst().orElse(PricingCatalog.DEFAULT_CURRENCY));
    }

    private static boolean sameScope(BudgetScope current, BudgetScope asked, String accountId) {
        String acct = asked.accountId() == null ? accountId : asked.accountId();
        return current.accountId().equals(acct) && java.util.Objects.equals(current.tag(), asked.tag());
    }

    static String hash(BudgetInput input) {
        String canonical = String.join("\u0000", String.valueOf(input.name()), String.valueOf(input.scope()),
                input.amount() == null ? "null" : input.amount().stripTrailingZeros().toPlainString(), String.valueOf(input.currency()),
                String.valueOf(input.thresholds()), String.valueOf(input.blockCreates()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
