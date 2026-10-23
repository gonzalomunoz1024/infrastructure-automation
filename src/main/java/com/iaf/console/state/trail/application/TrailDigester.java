package com.iaf.console.state.trail.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.kit.paging.Cursor;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.state.inventory.domain.ActivityQuery;
import com.iaf.console.state.inventory.domain.ResourceEvent;
import com.iaf.console.state.inventory.ports.outbound.ResourceEventStorePort;
import com.iaf.console.state.trail.domain.Digest;
import com.iaf.console.state.trail.domain.DigestRoot;
import com.iaf.console.state.trail.domain.DigestSigner;
import com.iaf.console.state.trail.ports.outbound.TrailDigestPort;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Seals the trail period by period: once a period (an hour by default, aligned to the epoch) has
 * ended and its grace has passed, every account with events in it gets a signed {@link Digest},
 * chained to the account's digest before it. The progress is shared, and moved with a
 * compare-and-set, so several replicas may run it: a digest is written once whoever computes it,
 * and they all compute the same one.
 */
public final class TrailDigester {

    private static final Logger log = LoggerFactory.getLogger(TrailDigester.class);
    /** How many events one read of a period takes. */
    static final int PAGE = 1000;
    /** How many periods one pass seals at most, so a long outage catches up in steps. */
    static final int MAX_PERIODS = 48;
    private static final String DIGESTER = "trail-digester";

    private final ResourceEventStorePort events;
    private final TrailDigestPort digests;
    private final DigestSigner signer;
    private final Clock clock;
    private final Duration period;
    private final Duration grace;
    private final Duration backfill;

    public TrailDigester(ResourceEventStorePort events, TrailDigestPort digests, DigestSigner signer, Clock clock, Duration period, Duration grace,
            Duration backfill) {
        if (period.isZero() || period.isNegative()) {
            throw new IllegalArgumentException("the trail's period is positive");
        }
        this.events = events;
        this.digests = digests;
        this.signer = signer;
        this.clock = clock;
        this.period = period;
        this.grace = grace;
        this.backfill = backfill;
    }

    public Duration period() {
        return period;
    }

    /** The start of the period an instant falls in. */
    public Instant periodOf(Instant at) {
        long size = period.toNanos();
        long ns = Mongo.nanos(at);
        return Mongo.fromNanos(Math.floorDiv(ns, size) * size);
    }

    /** Seals every period that is due; answers how many digests this pass wrote. */
    public Mono<Integer> sealDue() {
        Instant now = clock.instant();
        return digests.saveKey(signer.publicJwk())
                .then(digests.findSealedThrough().map(Optional::of).defaultIfEmpty(Optional.empty()))
                .flatMap(through -> sealFrom(through.orElse(null), through.orElseGet(() -> periodOf(now.minus(backfill))), now, 0, 0));
    }

    private Mono<Integer> sealFrom(Instant expected, Instant from, Instant now, int periods, int written) {
        Instant to = from.plus(period);
        if (to.plus(grace).isAfter(now) || periods >= MAX_PERIODS) {
            return Mono.just(written);
        }
        return seal(from, to).flatMap(n -> digests.advanceSealedThrough(expected, to).flatMap(moved -> {
            if (!moved) {
                return Mono.just(written + n); // another replica is ahead
            }
            return sealFrom(to, to, now, periods + 1, written + n);
        }));
    }

    /** The digests of one period: one per account and region with events in it. */
    Mono<Integer> seal(Instant from, Instant to) {
        return events.findAccountsWithEvents(from, to)
                .concatMap(account -> sealAccount(AccountScope.of(account.accountId(), account.region(), DIGESTER), from, to))
                .reduce(0, Integer::sum)
                .doOnNext(n -> {
                    if (n > 0) {
                        log.info("trail sealed {} to {}: {} digest(s)", from, to, n);
                    }
                });
    }

    private Mono<Integer> sealAccount(AccountScope scope, Instant from, Instant to) {
        DigestRoot root = new DigestRoot();
        return feed(scope, from, to, null, root)
                .then(digests.findLatestBefore(scope, from).map(d -> Optional.ofNullable(d.hash())).defaultIfEmpty(Optional.empty()))
                .flatMap(previous -> {
                    if (root.count() == 0) {
                        return Mono.just(0);
                    }
                    Digest digest = Digest.unsigned(scope.accountId(), scope.region(), from, to, root.count(), root.first(), root.last(), root.root(),
                            previous.orElse(null)).signed(signer, clock.instant());
                    return digests.save(digest).map(written -> written ? 1 : 0);
                });
    }

    /** Feeds a window's events into a root, page by page, in the log's time order. */
    Mono<Void> feed(AccountScope scope, Instant from, Instant to, Cursor after, DigestRoot root) {
        return events.findBetween(scope, from, to, after, PAGE).collectList().flatMap(page -> {
            page.forEach(root::add);
            if (page.size() < PAGE) {
                return Mono.<Void>empty();
            }
            ResourceEvent last = page.get(page.size() - 1);
            return feed(scope, from, to, new Cursor(Mongo.nanos(last.at()), ActivityQuery.idOf(last)), root);
        });
    }

    /** A window's events in the log's time order, page by page, at most {@code max} (one more tells the caller there were more). */
    static Flux<ResourceEvent> window(ResourceEventStorePort events, AccountScope scope, Instant from, Instant to, int max) {
        return page(events, scope, from, to, null, max);
    }

    private static Flux<ResourceEvent> page(ResourceEventStorePort events, AccountScope scope, Instant from, Instant to, Cursor after, int left) {
        if (left <= 0) {
            return Flux.empty();
        }
        int size = Math.min(PAGE, left);
        return events.findBetween(scope, from, to, after, size).collectList().flatMapMany(list -> {
            if (list.size() < size) {
                return Flux.fromIterable(list);
            }
            ResourceEvent last = list.get(list.size() - 1);
            return Flux.fromIterable(list)
                    .concatWith(page(events, scope, from, to, new Cursor(Mongo.nanos(last.at()), ActivityQuery.idOf(last)), left - list.size()));
        });
    }
}
