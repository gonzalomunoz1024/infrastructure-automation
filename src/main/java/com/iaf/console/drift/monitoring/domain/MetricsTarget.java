package com.iaf.console.drift.monitoring.domain;

import com.iaf.console.drift.drift.domain.StateResource;
import com.iaf.console.drift.drift.domain.Watch;

/**
 * The resource a metrics read is for: its wfrn, account, region and type, and the ids the platform
 * knows it by. It comes from the watch list when the resource is watched, else from the state
 * service, so a read needs no lookup of its own. An imported object ({@code source: discover}) has
 * no registry record, so the platform has no metrics read for it.
 */
public record MetricsTarget(String wfrn, String accountId, String region, String type, String name, String registryId, String providerId,
        boolean providerOnly) {

    public static MetricsTarget of(Watch watch) {
        return new MetricsTarget(watch.wfrn(), watch.accountId(), watch.region(), watch.type(), watch.name(), watch.registryId(), watch.providerId(),
                watch.providerOnly());
    }

    public static MetricsTarget of(StateResource resource) {
        return new MetricsTarget(resource.wfrn(), resource.accountId(), resource.region(), resource.type(), resource.name(), resource.registryId(),
                resource.providerId(), "discover".equals(resource.source()));
    }
}
