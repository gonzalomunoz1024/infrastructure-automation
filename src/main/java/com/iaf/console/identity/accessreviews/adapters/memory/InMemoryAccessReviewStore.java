package com.iaf.console.identity.accessreviews.adapters.memory;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

import com.iaf.console.identity.accessreviews.domain.AccessReview;
import com.iaf.console.identity.accessreviews.ports.outbound.AccessReviewStore;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.paging.Cursor;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The in-memory campaigns of the demo and the tests, with the Mongo store's rules: one open per account, the newest first. */
public final class InMemoryAccessReviewStore implements AccessReviewStore {

    /** Started last first; the id breaks a tie. */
    static final Comparator<AccessReview> NEWEST_FIRST = Comparator.comparing(AccessReview::startedAt).thenComparing(AccessReview::reviewId).reversed();

    private final Map<String, AccessReview> reviews = new ConcurrentSkipListMap<>();

    @Override
    public Mono<AccessReview> insert(AccessReview r) {
        return Mono.fromCallable(() -> {
            synchronized (reviews) {
                if (reviews.containsKey(r.reviewId())) {
                    throw new ConflictException("the access review id exists");
                }
                if (r.isOpen() && openOf(r.accountId(), r.reviewId()) != null) {
                    throw new ConflictException("an access review of the account is open");
                }
                reviews.put(r.reviewId(), r);
                return r;
            }
        });
    }

    @Override
    public Mono<AccessReview> update(AccessReview r, long expectedVersion) {
        return Mono.fromCallable(() -> {
            synchronized (reviews) {
                AccessReview current = reviews.get(r.reviewId());
                if (current == null || current.version() != expectedVersion || !current.accountId().equals(r.accountId())) {
                    return null;
                }
                if (r.isOpen() && openOf(r.accountId(), r.reviewId()) != null) {
                    throw new ConflictException("an access review of the account is open");
                }
                reviews.put(r.reviewId(), r);
                return r;
            }
        });
    }

    @Override
    public Mono<AccessReview> find(AccountScope scope, String reviewId) {
        return Mono.fromSupplier(() -> reviews.get(reviewId)).filter(r -> r.accountId().equals(scope.accountId()));
    }

    @Override
    public Mono<AccessReview> findOpen(AccountScope scope) {
        return Mono.fromSupplier(() -> openOf(scope.accountId(), null));
    }

    @Override
    public Mono<AccessReview> findLatest(AccountScope scope) {
        return Mono.fromSupplier(() -> reviews.values().stream().filter(r -> r.accountId().equals(scope.accountId())).sorted(NEWEST_FIRST).findFirst()
                .orElse(null));
    }

    @Override
    public Mono<Page<AccessReview>> list(AccountScope scope, int limit, String after) {
        return Mono.fromSupplier(() -> {
            Cursor cursor = after == null ? null : CursorPaging.decode(after);
            long afterStarted = cursor == null ? 0 : cursor.sortLong();
            List<AccessReview> fetched = reviews.values().stream()
                    .filter(r -> r.accountId().equals(scope.accountId()))
                    .filter(r -> cursor == null || nanos(r.startedAt()) < afterStarted
                            || nanos(r.startedAt()) == afterStarted && r.reviewId().compareTo(cursor.id()) < 0)
                    .sorted(NEWEST_FIRST)
                    .limit(limit + 1L)
                    .toList();
            return Page.from(fetched, limit, r -> new Cursor(nanos(r.startedAt()), r.reviewId()));
        });
    }

    @Override
    public Flux<AccessReview> findDue(Instant now) {
        return Flux.defer(() -> Flux.fromIterable(reviews.values().stream().filter(r -> r.isDue(now)).sorted(Comparator.comparing(AccessReview::dueAt))
                .toList()));
    }

    private AccessReview openOf(String accountId, String except) {
        return reviews.values().stream().filter(r -> r.accountId().equals(accountId) && r.isOpen() && !r.reviewId().equals(except)).findFirst()
                .orElse(null);
    }

    /** An instant as epoch nanoseconds, the cursor's sort value (the Mongo store's too). */
    static long nanos(Instant at) {
        return Math.addExact(Math.multiplyExact(at.getEpochSecond(), 1_000_000_000L), at.getNano());
    }
}
