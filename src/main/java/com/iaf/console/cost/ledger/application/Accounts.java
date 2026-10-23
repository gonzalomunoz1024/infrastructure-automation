package com.iaf.console.cost.ledger.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.iaf.console.cost.ledger.domain.AccountInfo;
import com.iaf.console.cost.ledger.ports.outbound.AccountDirectoryPort;

import reactor.core.publisher.Mono;

/** The account directory, cached for a while: names and cost centres change rarely and reports read them often. */
public final class Accounts {

    private final AccountDirectoryPort directory;
    private final Clock clock;
    private final Duration ttl;
    private final AtomicReference<Cached> cached = new AtomicReference<>();

    private record Cached(Map<String, AccountInfo> byId, Instant at) {
    }

    public Accounts(AccountDirectoryPort directory, Clock clock, Duration ttl) {
        this.directory = directory;
        this.clock = clock;
        this.ttl = ttl;
    }

    public Mono<Map<String, AccountInfo>> all() {
        Cached c = cached.get();
        if (c != null && c.at().plus(ttl).isAfter(clock.instant())) {
            return Mono.just(c.byId());
        }
        return directory.listAccounts().collect(Collectors.toMap(AccountInfo::accountId, Function.identity(), (a, b) -> b))
                .doOnNext(m -> cached.set(new Cached(Map.copyOf(m), clock.instant())))
                .onErrorResume(e -> c != null ? Mono.just(c.byId()) : Mono.error(e));
    }

    public Mono<AccountInfo> of(String accountId) {
        return all().map(m -> m.getOrDefault(accountId, AccountInfo.unknown(accountId))).onErrorReturn(AccountInfo.unknown(accountId));
    }

    /** Forgets the cache, so an account changed in the identity service is read again. */
    public void invalidate() {
        cached.set(null);
    }
}
