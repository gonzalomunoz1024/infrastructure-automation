package com.iaf.console.cost.ledger.adapters.memory;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.iaf.console.cost.ledger.domain.AccountLedger;
import com.iaf.console.cost.ledger.ports.outbound.LedgerStatePort;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Each account's ledger position, in one JVM. */
public final class InMemoryLedgers implements LedgerStatePort {

    private final Map<String, AccountLedger> ledgers = new ConcurrentHashMap<>();

    @Override
    public Mono<AccountLedger> find(AccountScope scope) {
        return Mono.justOrEmpty(ledgers.get(scope.accountId()));
    }

    @Override
    public Flux<AccountLedger> listAll() {
        return Flux.fromStream(() -> ledgers.values().stream().sorted(java.util.Comparator.comparing(AccountLedger::accountId)));
    }

    @Override
    public Mono<AccountLedger> createIfAbsent(AccountLedger ledger) {
        return Mono.fromSupplier(() -> ledgers.computeIfAbsent(ledger.accountId(), k -> ledger));
    }

    @Override
    public Mono<Void> markDirty(String accountId, LocalDate day) {
        return Mono.fromRunnable(() -> ledgers.computeIfPresent(accountId, (k, l) -> new AccountLedger(l.accountId(), l.firstDay(), l.closedThrough(),
                l.dirtyFrom() == null || day.isBefore(l.dirtyFrom()) ? day : l.dirtyFrom(), l.rev() + 1)));
    }

    @Override
    public Mono<Boolean> closed(String accountId, LocalDate closedThrough, long expectedRev) {
        return Mono.fromSupplier(() -> {
            boolean[] done = {false};
            ledgers.computeIfPresent(accountId, (k, l) -> {
                if (l.rev() != expectedRev) {
                    return l;
                }
                done[0] = true;
                LocalDate through = closedThrough.isAfter(l.closedThrough()) || l.dirtyFrom() != null ? closedThrough : l.closedThrough();
                return new AccountLedger(l.accountId(), l.firstDay(), through, null, l.rev());
            });
            return done[0];
        });
    }
}
