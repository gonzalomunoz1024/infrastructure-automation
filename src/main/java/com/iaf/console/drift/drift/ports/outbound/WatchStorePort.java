package com.iaf.console.drift.drift.ports.outbound;

import java.time.Instant;
import java.util.Collection;

import com.iaf.console.drift.drift.domain.Tier;
import com.iaf.console.drift.drift.domain.Watch;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The watch list: every managed resource with its desired state, tier and due time. */
@GlobalRead("the scanner, the sync and the webhooks work across accounts; the API's reads take a scope")
public interface WatchStorePort {

    Mono<Watch> find(String wfrn);

    Flux<Watch> findByWfrns(Collection<String> wfrns);

    /** The resources of a shard (and tier, when given) whose next check is due, oldest due first. */
    Flux<Watch> findDue(int shard, Tier tier, Instant now, int limit);

    Flux<Watch> findByProviderId(String providerId, String type);

    /** The accounts that have a watched resource. */
    Flux<String> listAccounts();

    /** An account's watches, for the operator's view and the demo. */
    Mono<Page<Watch>> list(AccountScope scope, String type, int limit, String after);

    /** When one of the account's resources was last read. */
    Mono<Instant> findLastCheckedAt(AccountScope scope);

    /**
     * Writes the watch if the stored one is still at {@code watch.rev()} (0: not stored yet) and
     * answers it at the next revision; otherwise fails with {@link Conflict}.
     */
    Mono<Watch> save(Watch watch);

    /** Another writer changed the watch since it was read. */
    final class Conflict extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Conflict(String wfrn) {
            super("the watch of " + wfrn + " changed since it was read");
        }
    }

    Mono<Void> delete(String wfrn);

    Mono<Long> count();
}
