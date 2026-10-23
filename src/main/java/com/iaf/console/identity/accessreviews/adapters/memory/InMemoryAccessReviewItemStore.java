package com.iaf.console.identity.accessreviews.adapters.memory;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

import com.iaf.console.identity.accessreviews.domain.AccessReviewItem;
import com.iaf.console.identity.accessreviews.ports.outbound.AccessReviewItemStore;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.paging.Cursor;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The in-memory items of the access reviews, in id order (the order they were made). */
public final class InMemoryAccessReviewItemStore implements AccessReviewItemStore {

    private final Map<String, AccessReviewItem> items = new ConcurrentSkipListMap<>();

    @Override
    public Mono<Void> insertAll(List<AccessReviewItem> made) {
        return Mono.fromRunnable(() -> {
            synchronized (items) {
                if (made.stream().anyMatch(i -> items.containsKey(i.itemId()))) {
                    throw new ConflictException("an access review item id exists");
                }
                made.forEach(i -> items.put(i.itemId(), i));
            }
        });
    }

    @Override
    public Mono<AccessReviewItem> update(AccessReviewItem item, long expectedVersion) {
        return Mono.fromCallable(() -> {
            synchronized (items) {
                AccessReviewItem current = items.get(item.itemId());
                if (current == null || current.version() != expectedVersion || !current.accountId().equals(item.accountId())) {
                    return null;
                }
                items.put(item.itemId(), item);
                return item;
            }
        });
    }

    @Override
    public Mono<AccessReviewItem> find(AccountScope scope, String reviewId, String itemId) {
        return Mono.fromSupplier(() -> items.get(itemId)).filter(i -> of(scope, reviewId, i));
    }

    @Override
    public Mono<Page<AccessReviewItem>> list(AccountScope scope, String reviewId, AccessReviewItem.Decision decision, int limit, String after) {
        return Mono.fromSupplier(() -> {
            String afterId = after == null ? null : CursorPaging.decode(after).id();
            List<AccessReviewItem> fetched = items.values().stream()
                    .filter(i -> of(scope, reviewId, i))
                    .filter(i -> decision == null || i.decision() == decision)
                    .filter(i -> afterId == null || i.itemId().compareTo(afterId) > 0)
                    .limit(limit + 1L)
                    .toList();
            return Page.from(fetched, limit, i -> new Cursor(i.itemId(), i.itemId()));
        });
    }

    @Override
    public Flux<AccessReviewItem> findPending(AccountScope scope, String reviewId) {
        return Flux.defer(() -> Flux.fromIterable(items.values().stream().filter(i -> of(scope, reviewId, i) && i.isPending()).toList()));
    }

    @Override
    public Mono<Map<AccessReviewItem.Decision, Long>> countByDecision(AccountScope scope, String reviewId) {
        return Mono.fromSupplier(() -> {
            Map<AccessReviewItem.Decision, Long> counts = new EnumMap<>(AccessReviewItem.Decision.class);
            items.values().stream().filter(i -> of(scope, reviewId, i)).forEach(i -> counts.merge(i.decision(), 1L, Long::sum));
            return counts;
        });
    }

    @Override
    public Mono<Void> deleteByReview(AccountScope scope, String reviewId) {
        return Mono.fromRunnable(() -> {
            synchronized (items) {
                items.values().removeIf(i -> of(scope, reviewId, i));
            }
        });
    }

    private static boolean of(AccountScope scope, String reviewId, AccessReviewItem i) {
        return i.accountId().equals(scope.accountId()) && i.reviewId().equals(reviewId);
    }
}
