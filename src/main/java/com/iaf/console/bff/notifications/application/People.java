package com.iaf.console.bff.notifications.application;

import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.iaf.console.bff.notifications.domain.AccountPeople;
import com.iaf.console.bff.notifications.domain.Draft;
import com.iaf.console.bff.notifications.ports.outbound.RecipientDirectory;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/**
 * An account's people for its notifications, read from the identity service as the console itself
 * and kept a short while per account; an identity change in the account drops them. Empty when
 * the identity service does not answer, which the recipients' rules allow for.
 */
public class People {

    /** The principal the console's own reads name in their scope. */
    public static final String CONSOLE = "console-bff";

    private static final Logger log = LoggerFactory.getLogger(People.class);

    private final RecipientDirectory directory;
    private final Cache<String, Mono<AccountPeople>> cache;

    public People(RecipientDirectory directory, Duration ttl) {
        this.directory = directory;
        this.cache = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(10_000).build();
    }

    public Mono<Optional<AccountPeople>> of(String accountId, String region) {
        if (!AccountScope.isAccountId(accountId)) {
            return Mono.just(Optional.empty());
        }
        Mono<AccountPeople> found = cache.get(accountId, id -> directory.findPeople(AccountScope.of(id, region == null || region.isBlank()
                ? Draft.NO_REGION : region, CONSOLE)).defaultIfEmpty(AccountPeople.none(id)).cache());
        return found.map(Optional::of).onErrorResume(e -> {
            cache.invalidate(accountId);
            log.warn("the identity service did not name the people of account {}: only the people events name are told: {}", accountId, e.toString());
            return Mono.just(Optional.empty());
        });
    }

    public void forget(String accountId) {
        cache.invalidate(accountId);
    }
}
