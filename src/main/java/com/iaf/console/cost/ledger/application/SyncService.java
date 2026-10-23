package com.iaf.console.cost.ledger.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.cost.ledger.domain.AccountInfo;
import com.iaf.console.cost.ledger.domain.SyncRun;
import com.iaf.console.cost.ledger.ports.outbound.AccountDirectoryPort;
import com.iaf.console.cost.ledger.ports.outbound.StateServicePort;
import com.iaf.console.cost.pricing.application.Pricing;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.wfrn.Ulid;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Re-reads accounts from the state service and corrects every rate: the answer to a lost event,
 * a restored backup, or a price list that changed (then only the types it re-priced, from now).
 * One sync runs at a time; a run is kept in memory with the last hundred, because a run is an
 * operator's tool and outlives no restart; an {@code Idempotency-Key} answers the run it started.
 */
public final class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);
    private static final int KEEP = 100;

    private final StateServicePort state;
    private final AccountDirectoryPort accounts;
    private final LedgerService ledger;
    private final Clock clock;
    private final Map<String, SyncRun> runs = new LinkedHashMap<>();
    private final Map<String, String> byKey = new LinkedHashMap<>();
    private final AtomicReference<String> running = new AtomicReference<>();

    public SyncService(StateServicePort state, AccountDirectoryPort accounts, LedgerService ledger, Pricing pricing, Clock clock) {
        this.state = state;
        this.accounts = accounts;
        this.ledger = ledger;
        this.clock = clock;
        pricing.onRepriced(types -> reprice(types).subscribeOn(Schedulers.boundedElastic()).subscribe(
                n -> log.info("re-priced {} resources of {}", n, types),
                e -> log.warn("re-pricing {} failed: {}", types, e.toString())));
    }

    /** Starts a sync of one account, or of all when {@code accountId} is null; 409 while one runs. */
    public synchronized Mono<SyncRun> start(String accountId, String idempotencyKey) {
        if (idempotencyKey != null && byKey.containsKey(idempotencyKey)) {
            return Mono.justOrEmpty(runs.get(byKey.get(idempotencyKey)));
        }
        if (running.get() != null) {
            return Mono.error(new ConflictException("a sync is running: " + running.get()));
        }
        SyncRun run = SyncRun.started("sync_" + Ulid.next().toLowerCase(Locale.ROOT), accountId, clock.instant());
        remember(run, idempotencyKey);
        running.set(run.runId());
        AtomicLong seen = new AtomicLong();
        Flux<String> ids = accountId != null ? Flux.just(accountId) : accounts.listAccounts().map(AccountInfo::accountId);
        ids.concatMap(id -> sync(id, seen)).reduce(0L, Long::sum)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(corrected -> finish(run.succeeded(seen.get(), corrected, clock.instant())),
                        e -> finish(run.failed(seen.get(), e.toString(), clock.instant())));
        return Mono.just(run);
    }

    public synchronized Mono<SyncRun> find(String runId) {
        SyncRun run = runs.get(runId);
        return run != null ? Mono.just(run) : Mono.error(NotFoundException.of("sync", runId));
    }

    /** Re-applies every resource of the account, live and deleted; how many rates it corrected. */
    private Mono<Long> sync(String accountId, AtomicLong seen) {
        AccountScope scope = LedgerService.self(accountId);
        return state.list(scope, null, false, null).concatWith(state.list(scope, null, true, null))
                .doOnNext(r -> seen.incrementAndGet())
                .concatMap(r -> ledger.apply(scope, r))
                .filter(Boolean::booleanValue).count();
    }

    /** Re-prices from now every live resource of the types a price list change touched. */
    public Mono<Long> reprice(Set<String> types) {
        return accounts.listAccounts().map(AccountInfo::accountId).concatMap(id -> {
            AccountScope scope = LedgerService.self(id);
            return Flux.fromIterable(types).concatMap(type -> state.list(scope, null, false, type))
                    .concatMap(r -> ledger.reprice(scope, r)).filter(Boolean::booleanValue).count();
        }).reduce(0L, Long::sum);
    }

    private synchronized void remember(SyncRun run, String key) {
        runs.put(run.runId(), run);
        if (key != null) {
            byKey.put(key, run.runId());
        }
        while (runs.size() > KEEP) {
            String oldest = runs.keySet().iterator().next();
            runs.remove(oldest);
            byKey.values().removeIf(oldest::equals);
        }
    }

    private synchronized void finish(SyncRun run) {
        runs.put(run.runId(), run);
        running.compareAndSet(run.runId(), null);
        log.info("sync {} {}: {} resources, {} corrected", run.runId(), run.status(), run.resources(), run.corrected());
    }

    /** When the last run started, for the status. */
    public synchronized Instant lastStartedAt() {
        return runs.values().stream().map(SyncRun::startedAt).max(Instant::compareTo).orElse(null);
    }
}
