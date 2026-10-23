package com.iaf.console.drift.drift.ports.outbound;

import com.iaf.console.drift.drift.domain.DiscoveryRun;
import com.iaf.console.drift.drift.domain.UnmanagedObject;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/** Discovery runs and the unmanaged objects they found, per account. */
public interface DiscoveryStorePort {

    Mono<DiscoveryRun> findRun(AccountScope scope, String runId);

    /** The run going for a type and region, when one is. */
    Mono<DiscoveryRun> findRunningRun(AccountScope scope, String type, String region);

    Mono<DiscoveryRun> saveRun(DiscoveryRun run);

    Mono<Page<UnmanagedObject>> listUnmanaged(AccountScope scope, String type, String region, int limit, String after);

    Mono<UnmanagedObject> findUnmanaged(AccountScope scope, String type, String region, String providerId);

    Mono<UnmanagedObject> saveUnmanaged(UnmanagedObject object);

    Mono<Void> deleteUnmanaged(AccountScope scope, String type, String region, String providerId);
}
