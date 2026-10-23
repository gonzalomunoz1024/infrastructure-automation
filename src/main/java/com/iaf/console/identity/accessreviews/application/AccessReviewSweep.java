package com.iaf.console.identity.accessreviews.application;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.identity.accessreviews.domain.AccessReview;
import com.iaf.console.identity.accessreviews.ports.outbound.AccessReviewStore;
import com.iaf.console.identity.accounts.domain.Account;
import com.iaf.console.identity.accounts.ports.outbound.AccountStore;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * What the access reviews do on their own, as of an instant: a campaign past its due date closes
 * and its pending items expire (the access is removed as a revoke would, but for the last active
 * owner's owners group), and, when scheduled campaigns are on, every active account without an open
 * campaign whose last campaign started a cadence ago or more (or, with none yet, that was created a
 * cadence ago or more) gets a {@code scheduled} campaign started by
 * {@value AccessReviewCampaigns#SYSTEM}. One campaign or account that fails is logged and left to
 * the next sweep; the others go on.
 */
public final class AccessReviewSweep {

    private static final Logger log = LoggerFactory.getLogger(AccessReviewSweep.class);

    /** What one sweep did: the campaigns it closed, the items it expired and the scheduled campaigns it started. */
    public record Result(int closed, int expired, int started) {
    }

    private final AccessReviewStore reviews;
    private final AccountStore accounts;
    private final AccessReviewCampaigns campaigns;
    private final boolean scheduled;
    private final Duration cadence;
    private final int dueInDays;

    public AccessReviewSweep(AccessReviewStore reviews, AccountStore accounts, AccessReviewCampaigns campaigns, boolean scheduled, Duration cadence,
            int dueInDays) {
        this.reviews = reviews;
        this.accounts = accounts;
        this.campaigns = campaigns;
        this.scheduled = scheduled;
        this.cadence = cadence;
        this.dueInDays = dueInDays;
    }

    public Mono<Result> sweep(Instant now) {
        AtomicInteger closed = new AtomicInteger();
        AtomicInteger expired = new AtomicInteger();
        AtomicInteger started = new AtomicInteger();
        Flux<AccessReview> closing = reviews.findDue(now)
                .concatMap(review -> campaigns.expire(scopeOf(review.accountId()), review, now)
                        .doOnNext(e -> {
                            expired.addAndGet(e.expired());
                            if (e.closed()) {
                                closed.incrementAndGet();
                            }
                        })
                        .thenReturn(review)
                        .onErrorResume(e -> {
                            log.warn("the access review {} of account {} did not close: {}", review.reviewId(), review.accountId(), e.toString());
                            return Mono.empty();
                        }));
        Flux<AccessReview> starting = Flux.defer(() -> scheduled ? due(now) : Flux.<Account>empty())
                .concatMap(account -> campaigns.start(scopeOf(account.accountId()), AccessReviewCampaigns.defaultName(AccessReview.Trigger.scheduled, now),
                                dueInDays, AccessReview.Trigger.scheduled, AccessReviewCampaigns.SYSTEM, now)
                        .doOnNext(r -> started.incrementAndGet())
                        // another sweep started it first
                        .onErrorResume(ConflictException.class, e -> Mono.empty())
                        .onErrorResume(e -> {
                            log.warn("no scheduled access review started for account {}: {}", account.accountId(), e.toString());
                            return Mono.empty();
                        }));
        return closing.thenMany(starting).then(Mono.fromSupplier(() -> new Result(closed.get(), expired.get(), started.get())));
    }

    /** The active accounts with no open campaign whose last campaign started, or which were created when they have none, a cadence ago or more. */
    private Flux<Account> due(Instant now) {
        return accounts.findAll()
                .filter(Account::isActive)
                .concatMap(account -> {
                    AccountScope scope = scopeOf(account.accountId());
                    Instant created = account.createdAt() == null ? now : account.createdAt();
                    return reviews.findOpen(scope).hasElement()
                            .filter(open -> !open)
                            .flatMap(none -> reviews.findLatest(scope).map(AccessReview::startedAt).defaultIfEmpty(created))
                            .filter(since -> !since.plus(cadence).isAfter(now))
                            .map(since -> account);
                });
    }

    private static AccountScope scopeOf(String accountId) {
        return AccountScope.of(accountId, "global", AccessReviewCampaigns.SYSTEM);
    }
}
