package com.iaf.console.identity.quotas.ports.outbound;

import com.iaf.console.kit.scope.GlobalRead;
import com.iaf.console.platform.model.Quota;

import reactor.core.publisher.Mono;

/** The platform's job-start bucket of an account (P-10): read through, and written when a {@code jobs-per-minute} limit is set. */
public interface PlatformQuotaPort {

    @GlobalRead
    Mono<Quota> getBucket(String accountId);

    Mono<Quota> putBucket(String accountId, int limitPerMinute, int burst);
}
