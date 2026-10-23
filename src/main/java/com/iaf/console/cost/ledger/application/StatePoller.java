package com.iaf.console.cost.ledger.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.cost.ledger.domain.AccountInfo;
import com.iaf.console.cost.ledger.domain.ResourceSnapshot;
import com.iaf.console.cost.ledger.ports.outbound.AccountDirectoryPort;
import com.iaf.console.cost.ledger.ports.outbound.CheckpointPort;
import com.iaf.console.cost.ledger.ports.outbound.StateServicePort;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Where Kafka is not there (the demo, a laptop), the ledger follows the state service by polling:
 * every account's resources changed after its watermark, live and deleted, each interval. The
 * watermark is moved back a second each time, so a change in the same instant is not missed; the
 * ledger skips what it already priced. A resource moved to another account is listed with the old
 * account's deleted ones ({@code transferredTo}, {@code deletedAt} the move), so its rate ends there
 * at the move as a delete's does, and with the new account's live ones from its import.
 */
public final class StatePoller implements EventSource {

    public static final String SOURCE = "poll";

    private static final Logger log = LoggerFactory.getLogger(StatePoller.class);
    private static final Duration OVERLAP = Duration.ofSeconds(1);

    private final StateServicePort state;
    private final AccountDirectoryPort accounts;
    private final LedgerService ledger;
    private final CheckpointPort checkpoints;
    private final Duration interval;
    private final AtomicLong handled = new AtomicLong();
    private final AtomicReference<Instant> lastEventAt = new AtomicReference<>();
    private final AtomicReference<String> lastError = new AtomicReference<>();
    private volatile Disposable ticking;

    public StatePoller(StateServicePort state, AccountDirectoryPort accounts, LedgerService ledger, CheckpointPort checkpoints,
            Duration interval) {
        this.state = state;
        this.accounts = accounts;
        this.ledger = ledger;
        this.checkpoints = checkpoints;
        this.interval = interval;
    }

    /** One pass over every account: how many resources moved the ledger. */
    public Mono<Long> tick() {
        return accounts.listAccounts().map(AccountInfo::accountId).concatMap(this::poll).reduce(0L, Long::sum)
                .doOnSuccess(n -> lastError.set(null))
                .doOnError(e -> {
                    lastError.set(e.toString());
                    log.warn("the poll failed: {}", e.toString());
                });
    }

    private Mono<Long> poll(String accountId) {
        AccountScope scope = LedgerService.self(accountId);
        String source = SOURCE + ":" + accountId;
        return checkpoints.find(source).map(Optional::of).defaultIfEmpty(Optional.empty()).flatMap(mark -> {
            Instant since = mark.map(m -> m.minus(OVERLAP)).orElse(null);
            AtomicReference<Instant> newest = new AtomicReference<>(mark.orElse(null));
            Flux<ResourceSnapshot> changed = state.list(scope, since, false, null).concatWith(state.list(scope, since, true, null));
            return changed.concatMap(r -> ledger.apply(scope, r).doOnNext(moved -> {
                Instant at = r.changedAt();
                if (at != null) {
                    newest.updateAndGet(n -> n == null || at.isAfter(n) ? at : n);
                    lastEventAt.updateAndGet(n -> n == null || at.isAfter(n) ? at : n);
                }
            })).filter(Boolean::booleanValue).count().flatMap(n -> {
                handled.addAndGet(n);
                Instant top = newest.get();
                return top == null || mark.map(top::equals).orElse(false) ? Mono.just(n) : checkpoints.save(source, top).thenReturn(n);
            }).onErrorResume(e -> {
                lastError.set(e.toString());
                log.warn("polling account {} failed: {}", accountId, e.toString());
                return Mono.just(0L);
            });
        });
    }

    @Override
    public synchronized void start() {
        if (ticking != null) {
            return;
        }
        log.info("following the state service by polling every {}", interval);
        ticking = Flux.interval(Duration.ZERO, interval, Schedulers.boundedElastic()).onBackpressureDrop()
                .concatMap(t -> tick().onErrorResume(e -> Mono.just(0L)), 1).subscribe();
    }

    @Override
    public synchronized void stop() {
        if (ticking != null) {
            ticking.dispose();
            ticking = null;
        }
    }

    @Override
    public boolean isRunning() {
        return ticking != null;
    }

    @Override
    public SourceStatus status() {
        return new SourceStatus(SOURCE, isRunning(), lastEventAt.get(), lastError.get(), handled.get());
    }
}
