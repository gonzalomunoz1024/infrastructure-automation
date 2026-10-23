package com.iaf.console.identity.quotas.adapters.platform;

import com.iaf.console.identity.quotas.domain.QuotaKeys;
import com.iaf.console.identity.quotas.ports.outbound.PlatformQuotaPort;
import com.iaf.console.platform.client.PlatformPort;
import com.iaf.console.platform.client.RequestOptions;
import com.iaf.console.platform.model.Quota;
import com.iaf.console.platform.model.QuotaUpdate;

import reactor.core.publisher.Mono;

/** P-10 over the platform client: {@code GET} and {@code PUT /api/v1/quotas/account:<accountId>}. */
public final class PlatformQuotaAdapter implements PlatformQuotaPort {

    private final PlatformPort platform;

    public PlatformQuotaAdapter(PlatformPort platform) {
        this.platform = platform;
    }

    @Override
    public Mono<Quota> getBucket(String accountId) {
        return platform.getQuota(QuotaKeys.platformKey(accountId));
    }

    @Override
    public Mono<Quota> putBucket(String accountId, int limitPerMinute, int burst) {
        return platform.putQuota(QuotaKeys.platformKey(accountId), new QuotaUpdate(limitPerMinute, burst), RequestOptions.none());
    }
}
