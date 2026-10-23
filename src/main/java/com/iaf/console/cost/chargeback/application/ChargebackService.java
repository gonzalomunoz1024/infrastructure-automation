package com.iaf.console.cost.chargeback.application;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import com.iaf.console.cost.chargeback.domain.ChargebackMonth;
import com.iaf.console.cost.chargeback.domain.ChargebackReport;
import com.iaf.console.cost.chargeback.domain.ChargebackRow;
import com.iaf.console.cost.chargeback.domain.Chargebacks;
import com.iaf.console.cost.chargeback.ports.outbound.ChargebackStorePort;
import com.iaf.console.cost.ledger.application.Accounts;
import com.iaf.console.cost.ledger.application.CostQueries;
import com.iaf.console.cost.ledger.domain.AccountInfo;
import com.iaf.console.cost.pricing.application.Pricing;
import com.iaf.console.cost.pricing.domain.Micros;
import com.iaf.console.cost.pricing.domain.PricingCatalog;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * A month's chargeback over one account or many: a published month as it was published, any
 * other from the ledger now (provisional). The report is final when every account's month is.
 */
public final class ChargebackService {

    private final ChargebackStorePort store;
    private final CostQueries costs;
    private final Accounts accounts;
    private final Pricing pricing;
    private final Clock clock;

    public ChargebackService(ChargebackStorePort store, CostQueries costs, Accounts accounts, Pricing pricing, Clock clock) {
        this.store = store;
        this.costs = costs;
        this.accounts = accounts;
        this.pricing = pricing;
        this.clock = clock;
    }

    /** The report and, for the CSV, the rendered body. */
    public record Rendered(ChargebackReport report, String csv) {
    }

    public Mono<Rendered> report(List<AccountScope> scopes, YearMonth month, String tagKey) {
        return Flux.fromIterable(scopes).flatMapSequential(s -> month(s, month), 4).collectList().zipWith(currency()).map(t -> {
            List<Part> parts = t.getT1();
            boolean isFinal = !parts.isEmpty() && parts.stream().allMatch(Part::published);
            String currency = parts.stream().map(p -> p.month().currency()).filter(c -> c != null).findFirst().orElse(t.getT2());
            List<ChargebackRow> rows = new ArrayList<>();
            List<ChargebackRow> plain = new ArrayList<>();
            for (Part p : parts) {
                rows.addAll(Chargebacks.rows(p.month(), tagKey));
                plain.addAll(Chargebacks.rows(p.month(), null));
            }
            List<ChargebackRow> sorted = Chargebacks.sorted(rows);
            String csv = Chargebacks.csv(month.toString(), currency, isFinal, sorted);
            String published = isFinal ? Chargebacks.sha256(Chargebacks.csv(month.toString(), currency, true, plain)) : null;
            ChargebackReport report = new ChargebackReport(month.toString(), currency, isFinal, Micros.toCents(Chargebacks.total(sorted)), sorted,
                    clock.instant(), published);
            return new Rendered(report, csv);
        });
    }

    private record Part(ChargebackMonth month, boolean published) {
    }

    private Mono<Part> month(AccountScope scope, YearMonth month) {
        return store.find(scope, month.toString()).map(m -> new Part(m, true))
                .switchIfEmpty(Mono.defer(() -> compute(scope, month, clock.instant()).map(m -> new Part(m, false))));
    }

    /** The account's month from the ledger as it stands, not published. */
    public Mono<ChargebackMonth> compute(AccountScope scope, YearMonth month, Instant at) {
        return Mono.zip(costs.bucketDays(scope, month.atDay(1), month.plusMonths(1).atDay(1)), accounts.of(scope.accountId()), currency())
                .map(t -> {
                    List<ChargebackMonth.Line> lines = Chargebacks.lines(t.getT1());
                    AccountInfo info = t.getT2();
                    long total = lines.stream().mapToLong(ChargebackMonth.Line::micros).sum();
                    ChargebackMonth m = new ChargebackMonth(scope.accountId(), month.toString(), info.name(), info.chargedTo(), t.getT3(), lines, total,
                            null, null, null);
                    String sha = Chargebacks.sha256(Chargebacks.csv(month.toString(), t.getT3(), true, Chargebacks.rows(m, null)));
                    return new ChargebackMonth(m.accountId(), m.month(), m.accountName(), m.costCentre(), m.currency(), lines, total, sha, at, null);
                });
    }

    /** The current month in UTC, which can never be final. */
    public YearMonth currentMonth() {
        return YearMonth.now(clock.withZone(ZoneOffset.UTC));
    }

    private Mono<String> currency() {
        return pricing.catalog().map(c -> c.lists().stream().map(l -> l.currency()).findFirst().orElse(PricingCatalog.DEFAULT_CURRENCY));
    }
}
