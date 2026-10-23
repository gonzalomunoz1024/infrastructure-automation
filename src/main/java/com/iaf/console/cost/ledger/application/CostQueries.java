package com.iaf.console.cost.ledger.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.iaf.console.cost.ledger.domain.AccountInfo;
import com.iaf.console.cost.ledger.domain.BucketDay;
import com.iaf.console.cost.ledger.domain.CostCell;
import com.iaf.console.cost.ledger.domain.CostFilter;
import com.iaf.console.cost.ledger.domain.CostQuery;
import com.iaf.console.cost.ledger.domain.CostReport;
import com.iaf.console.cost.ledger.domain.Forecasts;
import com.iaf.console.cost.ledger.domain.Granularity;
import com.iaf.console.cost.ledger.ports.outbound.DayStorePort;
import com.iaf.console.cost.ledger.ports.outbound.LedgerStatePort;
import com.iaf.console.cost.pricing.application.Pricing;
import com.iaf.console.cost.pricing.domain.Micros;
import com.iaf.console.cost.pricing.domain.PricingCatalog;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Answers the Cost Explorer: per account, the closed days' sums from the store and the open days
 * computed from the deltas, merged by period and group, the largest groups named and the rest
 * summed as {@code __other}, plus the run rate now, the unpriced resources and, when the range
 * covers this month, the month-end forecast. Grouping by account or cost centre is done here,
 * since every bucket of an account shares them.
 */
public final class CostQueries {

    /** The closed days the forecast's band is computed from. */
    public static final int FORECAST_DAYS = 14;

    private final LedgerStatePort ledgers;
    private final DayStorePort days;
    private final OpenDays open;
    private final Accounts accounts;
    private final Pricing pricing;
    private final java.time.Clock clock;

    public CostQueries(LedgerStatePort ledgers, DayStorePort days, OpenDays open, Accounts accounts, Pricing pricing, java.time.Clock clock) {
        this.clock = clock;
        this.ledgers = ledgers;
        this.days = days;
        this.open = open;
        this.accounts = accounts;
        this.pricing = pricing;
    }

    /** What one account contributes to a question: its cells, its current rows, how far it is closed. */
    record AccountPart(String accountId, List<CostCell> cells, List<BucketDay> current, LocalDate closedThrough, Instant now) {
    }

    public Mono<CostReport> report(List<AccountScope> scopes, CostQuery q) {
        Mono<Map<String, AccountInfo>> directory = q.groupsByAccount() ? accounts.all().onErrorReturn(Map.of()) : Mono.just(Map.of());
        return Mono.zip(parts(scopes, q).collectList(), directory, pricing.catalog()).flatMap(t -> {
            List<AccountPart> parts = t.getT1();
            Map<String, AccountInfo> infos = t.getT2();
            Mono<CostReport.Forecast> forecast = coversThisMonth(q, parts) ? forecast(scopes, q.filter(), parts) : Mono.empty();
            return forecast.map(java.util.Optional::of).defaultIfEmpty(java.util.Optional.empty())
                    .map(f -> build(q, parts, infos, t.getT3(), f.orElse(null)));
        });
    }

    /** The month-to-date spend and the month-end forecast for buckets passing a filter, in micros. */
    public Mono<Spend> spend(List<AccountScope> scopes, CostFilter filter) {
        return Flux.fromIterable(scopes).flatMap(s -> part(s, null, null, null, null, filter, true), 4).collectList().flatMap(parts -> {
            Instant now = parts.isEmpty() ? clock.instant() : parts.get(0).now();
            LocalDate today = now.atOffset(ZoneOffset.UTC).toLocalDate();
            LocalDate first = today.withDayOfMonth(1);
            return monthToDate(scopes, filter, first, today).flatMap(mtd -> recentDaily(scopes, filter, parts).map(recent -> {
                long runRate = parts.stream().flatMap(p -> p.current().stream()).filter(r -> filter.matches(r.bucket())).mapToLong(BucketDay::closingMicros).sum();
                Forecasts.Band band = Forecasts.forecast(mtd, runRate, now, recent);
                return new Spend(today.toString().substring(0, 7), mtd, runRate, band, now);
            }));
        });
    }

    /** A month's spend so far, the run rate now and the forecast band, in micros. */
    public record Spend(String period, long monthToDate, long runRate, Forecasts.Band band, Instant asOf) {
    }

    private Flux<AccountPart> parts(List<AccountScope> scopes, CostQuery q) {
        return Flux.fromIterable(scopes).flatMap(s -> part(s, q.from(), q.to(), q.granularity(), q.storeDimension(), q.filter(), false), 4);
    }

    /** One account's cells for a range (none when {@code currentOnly}) and its current rows. */
    private Mono<AccountPart> part(AccountScope scope, LocalDate from, LocalDate to, Granularity g, String dimension, CostFilter filter, boolean currentOnly) {
        return ledgers.find(scope).flatMap(ledger -> open.of(scope, ledger).flatMap(o -> {
            List<BucketDay> current = o.current();
            if (currentOnly) {
                return Mono.just(new AccountPart(scope.accountId(), List.of(), current, ledger.closedThrough(), o.now()));
            }
            LocalDate closedEnd = min(to, ledger.closedThrough().plusDays(1));
            Mono<List<CostCell>> closed = from.isBefore(closedEnd) ? days.sum(scope, from, closedEnd, g, dimension, filter).collectList() : Mono.just(List.of());
            return closed.map(cells -> {
                List<CostCell> all = new ArrayList<>(cells);
                for (BucketDay r : o.rows()) {
                    if (!r.day().isBefore(from) && r.day().isBefore(to) && filter.matches(r.bucket()) && r.amountMicros() != 0) {
                        String key = dimension == null ? CostCell.NONE : r.bucket().dimension(dimension);
                        all.add(new CostCell(g.periodOf(r.day()), key == null ? CostCell.NONE : key, r.amountMicros()));
                    }
                }
                return new AccountPart(scope.accountId(), all, current, ledger.closedThrough(), o.now());
            });
        })).switchIfEmpty(Mono.fromSupplier(() -> new AccountPart(scope.accountId(), List.of(), List.of(), null, clock.instant())));
    }

    private Mono<Long> monthToDate(List<AccountScope> scopes, CostFilter filter, LocalDate first, LocalDate today) {
        return total(scopes, first, today.plusDays(1), filter);
    }

    /** What the buckets passing a filter cost over the days {@code [from, to)}, closed and open, in micros. */
    public Mono<Long> total(List<AccountScope> scopes, LocalDate from, LocalDate to, CostFilter filter) {
        return Flux.fromIterable(scopes).flatMap(s -> part(s, from, to, Granularity.MONTH, null, filter, false), 4)
                .flatMapIterable(AccountPart::cells).map(CostCell::micros).reduce(0L, Long::sum);
    }

    /**
     * Every bucket's day of an account over the days {@code [from, to)}: the closed days as stored,
     * the open ones computed from their deltas. A month of chargeback is at most 31 reads.
     */
    public Mono<List<BucketDay>> bucketDays(AccountScope scope, LocalDate from, LocalDate to) {
        return ledgers.find(scope).flatMap(ledger -> {
            LocalDate closedEnd = min(to, ledger.closedThrough().plusDays(1));
            Flux<BucketDay> closed = Flux.empty();
            for (LocalDate d = from; d.isBefore(closedEnd); d = d.plusDays(1)) {
                closed = closed.concatWith(days.listDay(scope, d));
            }
            Mono<List<BucketDay>> openRows = closedEnd.isBefore(to) ? open.of(scope, ledger).map(o -> o.rows().stream()
                    .filter(r -> !r.day().isBefore(from) && r.day().isBefore(to)).toList()) : Mono.just(List.of());
            return closed.collectList().zipWith(openRows, (a, b) -> {
                List<BucketDay> all = new ArrayList<>(a);
                all.addAll(b);
                return all;
            });
        }).defaultIfEmpty(List.of());
    }

    /** The last day closed in an account (the day before its first when nothing is), empty for an account the ledger has not seen. */
    public Mono<LocalDate> closedThrough(AccountScope scope) {
        return ledgers.find(scope).map(com.iaf.console.cost.ledger.domain.AccountLedger::closedThrough);
    }

    /** The daily totals of the last closed days, all accounts together. */
    private Mono<List<Long>> recentDaily(List<AccountScope> scopes, CostFilter filter, List<AccountPart> parts) {
        LocalDate closed = parts.stream().map(AccountPart::closedThrough).filter(java.util.Objects::nonNull).min(LocalDate::compareTo).orElse(null);
        if (closed == null) {
            return Mono.just(List.of());
        }
        LocalDate from = closed.minusDays(FORECAST_DAYS - 1L);
        return Flux.fromIterable(scopes).flatMap(s -> days.sum(s, from, closed.plusDays(1), Granularity.DAY, null, filter), 4)
                .collect(TreeMap<LocalDate, Long>::new, (m, c) -> m.merge(c.period(), c.micros(), Long::sum))
                .map(m -> {
                    List<Long> out = new ArrayList<>();
                    for (LocalDate d = from; !d.isAfter(closed); d = d.plusDays(1)) {
                        out.add(m.getOrDefault(d, 0L));
                    }
                    return out;
                });
    }

    private Mono<CostReport.Forecast> forecast(List<AccountScope> scopes, CostFilter filter, List<AccountPart> parts) {
        return spend(scopes, filter).map(s -> new CostReport.Forecast(s.period(), Micros.toCents(s.monthToDate()), Micros.toCents(s.band().monthEnd()),
                Micros.toCents(s.band().low()), Micros.toCents(s.band().high()), Forecasts.METHOD));
    }

    private boolean coversThisMonth(CostQuery q, List<AccountPart> parts) {
        Instant now = parts.isEmpty() ? clock.instant() : parts.get(0).now();
        LocalDate today = now.atOffset(ZoneOffset.UTC).toLocalDate();
        LocalDate first = today.withDayOfMonth(1);
        return q.from().isBefore(first.plusMonths(1)) && q.to().isAfter(first);
    }

    private CostReport build(CostQuery q, List<AccountPart> parts, Map<String, AccountInfo> infos, PricingCatalog catalog, CostReport.Forecast forecast) {
        Map<LocalDate, Map<String, Long>> byPeriod = new TreeMap<>();
        Map<String, Long> byKey = new HashMap<>();
        for (AccountPart p : parts) {
            for (CostCell c : p.cells()) {
                String key = keyOf(q, p.accountId(), c.key(), infos);
                byPeriod.computeIfAbsent(c.period(), k -> new HashMap<>()).merge(key, c.micros(), Long::sum);
                byKey.merge(key, c.micros(), Long::sum);
            }
        }
        List<String> ranked = byKey.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey).toList();
        Set<String> named = new java.util.LinkedHashSet<>(ranked.subList(0, Math.min(q.top(), ranked.size())));
        List<CostReport.Group> groups = new ArrayList<>();
        long total = byKey.values().stream().mapToLong(Long::longValue).sum();
        if (q.groupBy() != null) {
            for (String k : named) {
                groups.add(new CostReport.Group(k, label(q, k, infos), Micros.toCents(byKey.get(k))));
            }
            long other = ranked.stream().filter(k -> !named.contains(k)).mapToLong(byKey::get).sum();
            if (other != 0) {
                groups.add(new CostReport.Group(CostCell.OTHER, "Other", Micros.toCents(other)));
            }
        }
        LocalDate closedThrough = parts.stream().map(AccountPart::closedThrough).filter(java.util.Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
        List<CostReport.Point> series = new ArrayList<>();
        for (LocalDate p = q.granularity().periodOf(q.from()); p.isBefore(q.to()); p = q.granularity().next(p)) {
            Map<String, Long> cells = byPeriod.getOrDefault(p, Map.of());
            Map<String, Double> split = new LinkedHashMap<>();
            if (q.groupBy() != null) {
                long other = 0;
                for (Map.Entry<String, Long> e : cells.entrySet()) {
                    if (named.contains(e.getKey())) {
                        split.put(e.getKey(), Micros.toCents(e.getValue()));
                    } else {
                        other += e.getValue();
                    }
                }
                if (other != 0) {
                    split.put(CostCell.OTHER, Micros.toCents(other));
                }
            }
            LocalDate periodEnd = q.granularity().next(p).minusDays(1);
            boolean provisional = closedThrough == null || periodEnd.isAfter(closedThrough);
            series.add(new CostReport.Point(p, Micros.toCents(cells.values().stream().mapToLong(Long::longValue).sum()), split, provisional));
        }
        long runRate = 0;
        long resources = 0;
        long unpriced = 0;
        Set<String> unpricedTypes = new TreeSet<>();
        for (AccountPart p : parts) {
            for (BucketDay r : p.current()) {
                if (q.filter().matches(r.bucket())) {
                    runRate += r.closingMicros();
                    resources += r.resources();
                    unpriced += r.unpriced();
                    if (r.unpriced() > 0) {
                        unpricedTypes.add(r.bucket().type());
                    }
                }
            }
        }
        List<CostReport.AccountRef> accountRefs = q.groupsByAccount() ? parts.stream().map(p -> {
            AccountInfo info = infos.getOrDefault(p.accountId(), AccountInfo.unknown(p.accountId()));
            return new CostReport.AccountRef(p.accountId(), info.name(), info.chargedTo());
        }).toList() : null;
        Instant now = parts.isEmpty() ? clock.instant() : parts.get(0).now();
        return new CostReport(currency(q, catalog), q.from(), q.to(), q.granularity().wire(), q.groupBy(), Micros.toCents(total), groups, series, forecast,
                new CostReport.RunRate(Micros.toCents(runRate), resources), now, closedThrough, CostReport.SOURCE,
                new CostReport.Unpriced(unpriced, List.copyOf(unpricedTypes)), accountRefs);
    }

    private static String keyOf(CostQuery q, String accountId, String cellKey, Map<String, AccountInfo> infos) {
        if ("account".equals(q.groupBy())) {
            return accountId;
        }
        if ("costCentre".equals(q.groupBy())) {
            return infos.getOrDefault(accountId, AccountInfo.unknown(accountId)).chargedTo();
        }
        return q.groupBy() == null ? CostCell.NONE : cellKey;
    }

    private static String label(CostQuery q, String key, Map<String, AccountInfo> infos) {
        if (CostCell.NONE.equals(key) && q.groupBy() != null && q.groupBy().startsWith("tag:")) {
            return "No " + q.groupBy().substring(4) + " tag";
        }
        if ("account".equals(q.groupBy())) {
            AccountInfo info = infos.get(key);
            return info != null && info.name() != null ? info.name() : key;
        }
        return key;
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }

    /** The currency of the type asked about, else the price lists' default (every list in force is USD). */
    private static String currency(CostQuery q, PricingCatalog catalog) {
        return q.filter().type() != null ? catalog.currencyOf(q.filter().type()) : PricingCatalog.DEFAULT_CURRENCY;
    }
}
