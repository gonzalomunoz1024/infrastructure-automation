package com.iaf.console.drift.drift.ports.outbound;

import com.iaf.console.drift.drift.domain.DiscoveryRun;
import com.iaf.console.drift.drift.domain.UnmanagedObject;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Discovery runs and the unmanaged objects they found, per account: the copy of the account whose
 * run found an object, and the copy of the account it was assigned to.
 */
public interface DiscoveryStorePort {

    /** Every unmanaged object by default; {@code mine} those guessed to be or assigned to the account; {@code unknown} those with no guess. */
    String ANY = "any";
    String MINE = "mine";
    String UNKNOWN = "unknown";

    Mono<DiscoveryRun> findRun(AccountScope scope, String runId);

    /** The run going for a type and region, when one is. */
    Mono<DiscoveryRun> findRunningRun(AccountScope scope, String type, String region);

    Mono<DiscoveryRun> saveRun(DiscoveryRun run);

    /** One page of the account's unmanaged objects, of one type or every type, filtered by owner ({@link #ANY} or null for all). */
    Mono<Page<UnmanagedObject>> listUnmanaged(AccountScope scope, String type, String region, String owner, int limit, String after);

    Mono<UnmanagedObject> findUnmanaged(AccountScope scope, String type, String region, String providerId);

    Mono<UnmanagedObject> saveUnmanaged(UnmanagedObject object);

    Mono<Void> deleteUnmanaged(AccountScope scope, String type, String region, String providerId);

    /** Every account's copy of one provider object: the finder's and the one it was handed to. */
    @GlobalRead("an assignment hands an object from one account to another, so both copies are looked up together")
    Flux<UnmanagedObject> findCopies(String type, String region, String providerId);
}
