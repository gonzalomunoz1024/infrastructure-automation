package com.iaf.console.state.inventory.ports.outbound;

import java.time.Instant;
import java.util.Comparator;

import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.kit.scope.GlobalRead;
import com.iaf.console.kit.paging.Cursor;
import com.iaf.console.state.inventory.domain.ActivityQuery;
import com.iaf.console.state.inventory.domain.ResourceEvent;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The log: every event of every resource, unique on {@code (wfrn, version)}, so two writers that
 * both think they hold version n cannot both append n+1 (the second gets a 409 and starts over).
 */
public interface ResourceEventStorePort {

    /** Appends the event; a {@code ConflictException} ({@code version.conflict}) when its version is taken. */
    Mono<ResourceEvent> append(ResourceEvent event);

    /** The events of a resource after a version, in version order, at most {@code limit}. */
    Flux<ResourceEvent> findEvents(AccountScope scope, String wfrn, long afterVersion, int limit);

    /** The order of an account's log in time: by instant, then by {@link ActivityQuery#idOf} as text. */
    Comparator<ResourceEvent> TIME_ORDER = Comparator.comparing(ResourceEvent::at).thenComparing(ActivityQuery::idOf);

    /** One page of an account's events, newest first, at most {@code query.limit() + 1} so the caller can tell there is more. */
    Flux<ResourceEvent> findActivity(AccountScope scope, ActivityQuery query);

    /** An account's events in a window ({@code from} inclusive, {@code to} exclusive) in {@link #TIME_ORDER}, after a cursor, at most {@code limit}. */
    Flux<ResourceEvent> findBetween(AccountScope scope, Instant from, Instant to, Cursor after, int limit);

    /** The accounts and regions that have events in a window, for the trail's digests. */
    @GlobalRead("the trail's digester finds the accounts with events in a period, then reads each in its own scope")
    Flux<AccountRegion> findAccountsWithEvents(Instant from, Instant to);

    /** An account in a region. */
    record AccountRegion(String accountId, String region) {
    }

    /** Marks every event of a resource to expire at an instant: what a tombstone does to its log. */
    Mono<Void> expireAt(String wfrn, Instant at);

    /** How many events the log holds, for the health and the rebuild report. */
    @GlobalRead("a count of this service's own log")
    Mono<Long> countAll();
}
